package com.nxworker.tomcatcluster;

import org.apache.catalina.LifecycleException;
import org.apache.catalina.Manager;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;

/**
 * Created by b2c015 on 2015/10/14.
 */
public class RedisSessionRepository {

    private final Log log = LogFactory.getLog(RedisSessionRepository.class);

    protected byte[] NULL_SESSION = "null".getBytes();
    private JedisPool connectionPool;
    private int database = 0;

    public RedisSessionRepository(String host, int port, int timeout, String password, int database) throws LifecycleException {

        //初始化连接池
        try {
            // TODO: Allow configuration of pool (such as size...)
            connectionPool = new JedisPool(new JedisPoolConfig(), host, port, timeout, password);
        } catch (Exception e) {
            e.printStackTrace();
            throw new LifecycleException("Error Connecting to Redis", e);
        }
        this.database = database;
    }

    public Boolean remove(String sessionId) {
        Jedis jedis = null;
        try {
            jedis = acquireConnection();
            jedis.del(sessionId);
            return false;
        } finally {
            if (jedis != null) {
                jedis.close();
            }
            return true;
        }

    }

    public void clear() {
        Jedis jedis = null;
        Boolean error = true;
        try {
            jedis = acquireConnection();
            jedis.flushDB();
            error = false;
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    /**
     * 保存session
     */
    public boolean createNewSession(String sessionId) {
        Jedis jedis = null;
        try {
            jedis = acquireConnection();
            return jedis.setnx(sessionId.getBytes(), NULL_SESSION) == 1L;// 1 = key set; 0 = key already existed
        } catch (Exception e) {
            log.error("保存session失败", e);
            return false;
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    public void save(String id, byte[] data, int timeout) {
        Jedis jedis = null;
        try {

            byte[] binaryId = id.getBytes();

            jedis = acquireConnection();

            jedis.set(binaryId, data);

            log.trace("Setting expire timeout on session [" + id + "] to " + timeout);
            jedis.expire(binaryId, timeout);

        } catch (Exception e) {
            log.error(e.getMessage(), e);

        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    public RedisSession loadSession(String id, Manager manager, Serializer serializer) {

        RedisSession session = null;

        Jedis jedis = null;

        try {
            log.trace("Attempting to load session " + id + " from Redis");
            jedis = acquireConnection();
            byte[] data = jedis.get(id.getBytes());
            if (data == null) {
                log.trace("Session " + id + " not found in Redis");
                session = null;
            } else if (Arrays.equals(NULL_SESSION, data)) {
                log.trace("Session " + id + " may be expired in Redis");
                session = null;
                //throw new IllegalStateException("Race condition encountered: attempted to load session[" + id + "] which has been created but not yet serialized.");
            } else {
                log.trace("Deserializing session " + id + " from Redis");
                session = new RedisSession(manager);
                serializer.deserializeInto(data, session);
                session.setId(id);
                session.setNew(false);
                session.access();
                session.setValid(true);
                session.resetDirtyTracking();

                if (log.isTraceEnabled()) {
                    log.trace("Session Contents [" + id + "]:");
                    for (Object name : Collections.list(session.getAttributeNames())) {
                        log.trace("  " + name);
                    }
                }
            }
            return session;
        } catch (Exception e) {
            log.fatal(e.getMessage());
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
        return session;
    }

    public int getSize() throws IOException {
        Jedis jedis = null;
        Boolean error = true;
        try {
            jedis = acquireConnection();
            int size = jedis.dbSize().intValue();
            error = false;
            return size;
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    public String[] keys() throws IOException {
        Jedis jedis = null;
        Boolean error = true;
        try {
            jedis = acquireConnection();
            Set<String> keySet = jedis.keys("*");
            error = false;
            return keySet.toArray(new String[keySet.size()]);
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    public void close() {

        try {
            connectionPool.destroy();
        } catch (Exception e) {
            // Do nothing.
        }
    }

    private Jedis acquireConnection() {

        Jedis jedis = connectionPool.getResource();

        if (database != 0) {
            jedis.select(database);
        }

        return jedis;
    }
}
