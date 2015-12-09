package com.nxworker.tomcatcluster;

import org.apache.catalina.*;
import org.apache.catalina.util.LifecycleMBeanBase;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import redis.clients.jedis.Protocol;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.IOException;

/**
 * 自定义基于redis存储的clusterSessionManager
 */
public class ClusterSessionManager extends LifecycleMBeanBase implements Manager, PropertyChangeListener {

    private final   Log                   log     = LogFactory.getLog(ClusterSessionManager.class);
    protected final PropertyChangeSupport support = new PropertyChangeSupport(this);

    //默认session超时时间
    protected int maxInactiveInterval = 30 * 60;

    //存储
    private SessionRepository repository;

    //容器
    private Context context;

    //sessionId生成器
    private SessionIdGenerator sessionIdGenerator;

    //序列化用的类
    private String serializationStrategyClass = "com.nxworker.tomcatcluster.ClusterSerializer";


    private ThreadLocal<Session> currentSession   = new ThreadLocal<Session>();
    private ThreadLocal<String>  currentSessionId = new ThreadLocal<String>();

    //默认存储配置
    private String host     = "localhost";
    private int    port     = 6379;
    private int    database = 0;
    private String password = null;
    private int    timeout  = Protocol.DEFAULT_TIMEOUT;

    @Override
    public Container getContainer() {

        return getContext();
    }

    @Override
    public void setContainer(Container container) {

        if (container instanceof Context || container == null) {
            setContext((Context) container);
        } else {
            log.warn("set wrong type container to clusterSessionManager!");
        }

    }

    @Override
    public Context getContext() {

        return context;
    }

    @Override
    public void setContext(Context context) {

        // De-register from the old Context (if any)
        if (this.context != null) {
            this.context.removePropertyChangeListener(this);
        }

        Context oldContext = this.context;
        this.context = context;
        support.firePropertyChange("context", oldContext, this.context);

        // Register with the new Context (if any)
        if (this.context != null) {
            setMaxInactiveInterval(this.context.getSessionTimeout() * 60);
            this.context.addPropertyChangeListener(this);
        }
    }

    @Override
    public boolean getDistributable() {

        return false;
    }

    @Override
    public void setDistributable(boolean distributable) {

    }

    @Override
    public int getMaxInactiveInterval() {

        return maxInactiveInterval;
    }

    @Override
    public void setMaxInactiveInterval(int interval) {

        int oldMaxInactiveInterval = this.maxInactiveInterval;
        this.maxInactiveInterval = interval;
        support.firePropertyChange("maxInactiveInterval",
                Integer.valueOf(oldMaxInactiveInterval),
                Integer.valueOf(this.maxInactiveInterval));
    }

    @Override
    public SessionIdGenerator getSessionIdGenerator() {

        return this.sessionIdGenerator;
    }

    @Override
    public void setSessionIdGenerator(SessionIdGenerator sessionIdGenerator) {

        this.sessionIdGenerator = sessionIdGenerator;
    }

    @Override
    public int getSessionIdLength() {

        return 0;
    }

    @Override
    public void setSessionIdLength(int idLength) {

    }

    @Override
    public long getSessionCounter() {

        return 0;
    }

    @Override
    public void setSessionCounter(long sessionCounter) {

    }

    @Override
    public int getMaxActive() {

        return 0;
    }

    @Override
    public void setMaxActive(int maxActive) {

    }

    @Override
    public int getActiveSessions() {

        return 0;
    }

    @Override
    public long getExpiredSessions() {

        return 0;
    }

    @Override
    public void setExpiredSessions(long expiredSessions) {

    }

    @Override
    public int getRejectedSessions() {

        return 0;
    }

    @Override
    public int getSessionMaxAliveTime() {

        return 0;
    }

    @Override
    public void setSessionMaxAliveTime(int sessionMaxAliveTime) {

    }

    @Override
    public int getSessionAverageAliveTime() {

        return 0;
    }

    @Override
    public int getSessionCreateRate() {

        return 0;
    }

    @Override
    public int getSessionExpireRate() {

        return 0;
    }


    /**
     * 添加session到存储里面
     *
     * @param session
     */
    @Override
    public void add(Session session) {

        log.info("保存session中");
        repository.save(session);
        repository.expire(session, session.getMaxInactiveInterval());
    }

    @Override
    public void addPropertyChangeListener(PropertyChangeListener listener) {

        support.addPropertyChangeListener(listener);
    }

    @Override
    public void changeSessionId(Session session) {

        String newId = generateSessionId();
        changeSessionId(session, newId, true, true);
    }

    private String generateSessionId() {

        return this.sessionIdGenerator.generateSessionId();
    }

    @Override
    public void changeSessionId(Session session, String newId) {

        changeSessionId(session, newId, true, true);
    }

    private void changeSessionId(Session session, String newId,
                                 boolean notifySessionListeners, boolean notifyContainerListeners) {

        String oldId = session.getIdInternal();
        session.setId(newId, false);
        session.tellChangedSessionId(newId, oldId,
                notifySessionListeners, notifyContainerListeners);
    }

    /**
     * 创建空session
     *
     * @return
     */
    @Override
    public Session createEmptySession() {

        return new ClusterSession(this);
    }

    /**
     * 创建session
     *
     * @param sessionId
     * @return
     */
    @Override
    public Session createSession(String sessionId) {

        Session session = createEmptySession();
        session.setNew(true);
        session.setValid(true);
        session.setCreationTime(System.currentTimeMillis());
        session.setMaxInactiveInterval(this.maxInactiveInterval);
        String id = sessionId;
        if (id == null) {
            id = generateSessionId();
        }
        session.setId(id);
        log.info("创建新session,sessionId为" + id);

        add(session);
        currentSession.set(session);
        currentSessionId.set(id);
        return session;
    }

    /**
     * 从存储里面获取session
     *
     * @param id
     * @return
     * @throws IOException
     */
    @Override
    public Session findSession(String id) throws IOException {

        log.info("sessionId:" + id);
        if (id == null) {
            return (null);
        }

        //先到当前线程找
        Session session;
        if (id.equals(currentSessionId.get())) {

            session = currentSession.get();

            if (session != null) {

                return session;
            }
        }

        //找不到到存储里面找
        session = repository.find(id, this);
        if (session == null) {

            System.out.println("没找到session");
        }
        return session;
    }

    @Override
    public Session[] findSessions() {

        return new Session[0];
    }

    @Override
    public void load() throws ClassNotFoundException, IOException {

    }

    /**
     * 从存储里面删除session
     *
     * @param session
     */
    @Override
    public void remove(Session session) {

        if (session != null && session.getIdInternal() != null) {
            repository.remove(session);
        }
    }

    /**
     * 从存储里面删除session
     *
     * @param session
     * @param update
     */
    @Override
    public void remove(Session session, boolean update) {

        remove(session);
    }

    @Override
    public void removePropertyChangeListener(PropertyChangeListener listener) {

        support.removePropertyChangeListener(listener);
    }

    @Override
    public void unload() throws IOException {

    }

    @Override
    public void backgroundProcess() {

    }

    /**
     * 组件属性修改
     *
     * @param event
     */
    @Override
    public void propertyChange(PropertyChangeEvent event) {

        // Validate the source of this event
        if (!(event.getSource() instanceof Context))
            return;

        // Process a relevant property change
        if (event.getPropertyName().equals("sessionTimeout")) {
            try {
                setMaxInactiveInterval(
                        ((Integer) event.getNewValue()).intValue() * 60);
            } catch (NumberFormatException e) {
                log.error("sessionTimeout");
            }
        }

    }

    // -------------------- JMX and Registration  --------------------
    @Override
    public String getObjectNameKeyProperties() {

        StringBuilder name = new StringBuilder("type=Manager");

        name.append(",host=");
        name.append(context.getParent().getName());

        name.append(",context=");
        String contextName = context.getName();
        if (!contextName.startsWith("/")) {
            name.append('/');
        }
        name.append(contextName);

        return name.toString();
    }

    @Override
    public String getDomainInternal() {

        return context.getDomain();
    }


    /**
     * 启动初始化
     *
     * @throws LifecycleException
     */
    @Override
    protected void startInternal() throws LifecycleException {

        setState(LifecycleState.STARTING);

        //实例化session id生成器
        SessionIdGenerator sessionIdGenerator = getSessionIdGenerator();
        if (sessionIdGenerator == null) {
            sessionIdGenerator = new ClusterSessionIdGenerator();
            setSessionIdGenerator(sessionIdGenerator);
        }

        if (sessionIdGenerator instanceof Lifecycle) {
            ((Lifecycle) sessionIdGenerator).start();
        }

        //加载session
        try {
            load();
        } catch (Throwable t) {
            log.error(t);
        }

        //初始化存储
        try {
            initRepository();
        } catch (Exception e) {
            log.error(e);
        }

        setDistributable(true);
    }

    /**
     * 初始化redis存储
     */
    private void initRepository() throws Exception {

        Serializer serializer = (Serializer) Class.forName(serializationStrategyClass).newInstance();
        serializer.setClassLoader(getContext().getLoader().getClassLoader());
        this.repository = new RedisRepository(serializer, getHost(), getPort(), getTimeout(), getPassword(), getDatabase());
    }


    /**
     * 停止
     *
     * @throws LifecycleException
     */
    @Override
    protected void stopInternal() throws LifecycleException {

        setState(LifecycleState.STOPPING);
        repository.close();
        if (sessionIdGenerator instanceof Lifecycle) {
            ((Lifecycle) sessionIdGenerator).stop();
        }
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

    public String getPassword() {

        return password;
    }

    public void setPassword(String password) {

        this.password = password;
    }

    public int getTimeout() {

        return timeout;
    }

    public void setTimeout(int timeout) {

        this.timeout = timeout;
    }

    public int getDatabase() {

        return database;
    }

    public void setDatabase(int database) {

        this.database = database;
    }
}
