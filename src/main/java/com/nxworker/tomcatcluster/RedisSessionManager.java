package com.nxworker.tomcatcluster;

import org.apache.catalina.*;
import org.apache.catalina.session.ManagerBase;
import org.apache.catalina.util.LifecycleSupport;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Protocol;

import java.io.IOException;


public class RedisSessionManager extends ManagerBase implements Lifecycle {

    private final Log log = LogFactory.getLog(RedisSessionManager.class);

    protected String host = "localhost";
    protected int port = 6379;
    protected int database = 0;
    protected String password = null;
    protected int timeout = Protocol.DEFAULT_TIMEOUT;
    protected JedisPool connectionPool;
    protected boolean distributable = true;
    protected RedisSessionHandlerValve handlerValve;
    protected ThreadLocal<RedisSession> currentSession = new ThreadLocal<RedisSession>();
    protected ThreadLocal<String> currentSessionId = new ThreadLocal<String>();
    protected ThreadLocal<Boolean> currentSessionIsPersisted = new ThreadLocal<Boolean>();
    protected Serializer serializer;
    protected RedisSessionRepository redisSessionRepository;
    protected static String name = "com.nxworker.tomcatcluster.RedisSessionManager";

    protected String serializationStrategyClass = "com.nxworker.tomcatcluster.JavaSerializer";

    /**
     * The lifecycle event support for this component.
     */
    protected LifecycleSupport lifecycle = new LifecycleSupport(this);

    /**
     * Add a lifecycle event listener to this component.
     *
     * @param listener The listener to add
     */
    public void addLifecycleListener(LifecycleListener listener) {
        lifecycle.addLifecycleListener(listener);
    }

    /**
     * Get the lifecycle listeners associated with this lifecycle. If this
     * Lifecycle has no listeners registered, a zero-length array is returned.
     */
    public LifecycleListener[] findLifecycleListeners() {
        return lifecycle.findLifecycleListeners();
    }


    /**
     * Remove a lifecycle event listener from this component.
     *
     * @param listener The listener to remove
     */
    public void removeLifecycleListener(LifecycleListener listener) {
        lifecycle.removeLifecycleListener(listener);
    }

    /**
     * Start this component and implement the requirements
     * of {@link org.apache.catalina.util.LifecycleBase#startInternal()}.
     *
     * @throws LifecycleException if this component detects a fatal error
     *                            that prevents this component from being used
     */
    @Override
    protected synchronized void startInternal() throws LifecycleException {
        super.startInternal();

        setState(LifecycleState.STARTING);

        Boolean attachedToValve = false;
        for (Valve valve : getContext().getPipeline().getValves()) {
            if (valve instanceof RedisSessionHandlerValve) {
                this.handlerValve = (RedisSessionHandlerValve) valve;
                this.handlerValve.setRedisSessionManager(this);
                log.info("Attached to com.nxworker.tomcatcluster.RedisSessionHandlerValve");
                attachedToValve = true;
                break;
            }
        }

        if (!attachedToValve) {
            String error = "Unable to attach to session handling valve; sessions cannot be saved after the request without the valve starting properly.";
            log.fatal(error);
            throw new LifecycleException(error);
        }

        try {
            initializeSerializer();
        } catch (ClassNotFoundException e) {
            log.fatal("Unable to load serializer", e);
            throw new LifecycleException(e);
        } catch (InstantiationException e) {
            log.fatal("Unable to load serializer", e);
            throw new LifecycleException(e);
        } catch (IllegalAccessException e) {
            log.fatal("Unable to load serializer", e);
            throw new LifecycleException(e);
        }

        log.info("Will expire sessions after " + getMaxInactiveInterval() + " seconds");

        initRepository();

        setDistributable(true);
    }


    /**
     * Stop this component and implement the requirements
     * of {@link org.apache.catalina.util.LifecycleBase#stopInternal()}.
     *
     * @throws LifecycleException if this component detects a fatal error
     *                            that prevents this component from being used
     */
    @Override
    protected synchronized void stopInternal() throws LifecycleException {
        if (log.isDebugEnabled()) {
            log.debug("Stopping");
        }
        setState(LifecycleState.STOPPING);
        redisSessionRepository.close();
        // Require a new random number generator if we are restarted
        super.stopInternal();
    }

    protected String generateSessionId() {

        return IdGenerator.getUUIDString();
    }

    @Override
    public Session createSession(String sessionId) {
        RedisSession session = (RedisSession) createEmptySession();

        // Initialize the properties of the new session and return it
        session.setNew(true);
        session.setValid(true);
        session.setCreationTime(System.currentTimeMillis());
        session.setMaxInactiveInterval(getMaxInactiveInterval());

        String jvmRoute = getJvmRoute();

        // 保证新生成的sessionId是唯一的
        do {
            if (null == sessionId) {
                sessionId = generateSessionId();
            }

            if (jvmRoute != null) {
                sessionId += '.' + jvmRoute;
            }
        }
        while (redisSessionRepository.createNewSession(sessionId));

      /* Even though the key is set in Redis, we are not going to flag
         the current thread as having had the session persisted since
         the session isn't actually serialized to Redis yet.
         This ensures that the save(session) at the end of the request
         will serialize the session into Redis with 'set' instead of 'setnx'. */

        session.setId(sessionId);
        session.tellNew();

        currentSession.set(session);
        currentSessionId.set(sessionId);
        currentSessionIsPersisted.set(false);

        return session;
    }

    @Override
    public Session createEmptySession() {
        return new RedisSession(this);
    }

    @Override
    public void add(Session session) {
        try {
            save(session);
        } catch (IOException ex) {
            log.warn("Unable to add to session manager store: " + ex.getMessage());
            throw new RuntimeException("Unable to add to session manager store.", ex);
        }
    }

    @Override
    public Session findSession(String id) throws IOException {
        RedisSession session;

        if (id == null) {
            session = null;
            currentSessionIsPersisted.set(false);
        } else if (id.equals(currentSessionId.get())) {
            session = currentSession.get();
        } else {
            session = loadSessionFromRedis(id);

            if (session != null) {
                currentSessionIsPersisted.set(true);
            }
        }

        currentSession.set(session);
        currentSessionId.set(id);

        return session;
    }

    public RedisSession loadSessionFromRedis(String id) throws IOException {

        RedisSession session = redisSessionRepository.loadSession(id, this, serializer);

        if (session != null) {
            session.setMaxInactiveInterval(getMaxInactiveInterval());
        }

        return session;

    }

    public void save(Session session) throws IOException {

        log.trace("保存session");
        RedisSession redisSession = (RedisSession) session;
        Boolean sessionIsDirty = redisSession.isDirty();
        redisSession.resetDirtyTracking();
        if (sessionIsDirty || currentSessionIsPersisted.get() != true) {
            redisSessionRepository.save(redisSession.getId(), serializer.serializeFrom(redisSession), getMaxInactiveInterval() * 1000);
        }
        currentSessionIsPersisted.set(true);
    }

    public void remove(Session session) {
        redisSessionRepository.remove(session.getId());
    }

    public void afterRequest() {
        RedisSession redisSession = currentSession.get();
        if (redisSession != null) {
            currentSession.remove();
            currentSessionId.remove();
            currentSessionIsPersisted.remove();
            log.trace("Session removed from ThreadLocal :" + redisSession.getIdInternal());
        }
    }

    /**
     * 初始化存储
     */
    private void initRepository() {

        try {
            redisSessionRepository = new RedisSessionRepository(getHost(), getPort(), getTimeout(), getPassword(), getDatabase());
        } catch (LifecycleException e) {
            e.printStackTrace();
        }
    }

    private void initializeSerializer() throws ClassNotFoundException, IllegalAccessException, InstantiationException {

        serializer = (Serializer) Class.forName(serializationStrategyClass).newInstance();
        serializer.setClassLoader(getContext().getLoader().getClassLoader());
    }

    @Override
    public void processExpires() {
        //通过设置redis的超时时间来实现超时，在这个方法里面不需要处理
    }

    public int getRejectedSessions() {
        // Essentially do nothing.
        return 0;
    }

    public void setRejectedSessions(int i) {
        // Do nothing.
    }


    public void load() throws ClassNotFoundException, IOException {
    }

    public void unload() throws IOException {
    }

    public void setSerializationStrategyClass(String strategy) {
        this.serializationStrategyClass = strategy;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public int getDatabase() {
        return database;
    }

    public void setDatabase(int database) {
        this.database = database;
    }

    public int getTimeout() {
        return timeout;
    }

    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
