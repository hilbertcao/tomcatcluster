package com.nxworker.tomcatcluster;

import org.apache.catalina.Manager;
import org.apache.catalina.Session;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;

/**
 * 基于redis的session存储
 */
public class RedisRepository implements SessionRepository {

    private final Log log = LogFactory.getLog(RedisRepository.class);

    private JedisPool connectionPool;
    private int database = 0;
    private Serializer serializer;
    private byte[] NULL_SESSION = "null".getBytes();

    public RedisRepository(Serializer serializer, String host, int port, int timeout, String password, int database) {

        this.serializer = serializer;
        try {
            //redis连接池
            JedisPoolConfig config = new JedisPoolConfig();
            config.setFairness(true);
            config.setMaxTotal(500);
            config.setMaxWaitMillis(1000 * 100);
            config.setMaxIdle(50);
            config.setBlockWhenExhausted(true);
            config.setTestOnCreate(true);
            config.setTestOnReturn(true);
            config.setTestWhileIdle(true);
            config.setTestOnBorrow(true);
            connectionPool = new JedisPool(config, host, port, timeout);
            this.database = database;
        } catch (Exception e) {
            e.printStackTrace();
            log.error(e);
        }
    }

    /**
     * 获取redis连接
     *
     * @return
     */
    private Jedis acquireConnection() {

        Jedis jedis = connectionPool.getResource();

/*
        if (getDatabase() != 0) {
            jedis.select(getDatabase());
        }
*/

        return jedis;
    }

    /**
     * 关闭redis连接池
     */
    public void close() {

        try {
            connectionPool.destroy();
        } catch (Exception e) {
            log.error(e);
        }
    }

    public void expire(Session session, int seconds) {

        Jedis  jedis    = acquireConnection();
        byte[] binaryId = session.getId().getBytes();
        log.trace("Setting expire timeout on session [" + session.getId() + "] to " + session.getMaxInactiveInterval());
        jedis.expire(binaryId, session.getMaxInactiveInterval());
    }

    /**
     * 保存session到redis
     *
     * @param session
     */
    public void save(Session session) {

        Jedis jedis = null;

        try {
            log.trace("Saving session " + session + " into Redis");

            ClusterSession redisSession = (ClusterSession) session;

            if (log.isTraceEnabled()) {
                log.trace("Session Contents [" + redisSession.getId() + "]:");
                for (Object name : Collections.list(redisSession.getAttributeNames())) {
                    log.trace("  " + name);
                }
            }

            redisSession.resetDirtyTracking();
            byte[] binaryId = redisSession.getId().getBytes();

            jedis = acquireConnection();

            jedis.set(binaryId, serializer.serializeFrom(redisSession));

        } catch (Exception e) {
            log.error(e.getMessage());
            e.printStackTrace();
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    /**
     * 从redis加载session
     *
     * @param id
     * @param manager
     * @return
     * @throws IOException
     */
    public Session find(String id, Manager manager) throws IOException {

        ClusterSession session;
        Jedis          jedis = null;
        try {

            jedis = acquireConnection();
            byte[] data = jedis.get(id.getBytes());

            if (data == null) {
                return null;
            } else if (Arrays.equals(NULL_SESSION, data)) {
                log.trace("Session " + id + " may be expired in Redis");
                return null;
            } else {
                log.trace("Deserializing session " + id + " from Redis");
                session = (ClusterSession) manager.createEmptySession();
                serializer.deserializeInto(data, session);
                session.setId(id);
                session.setNew(false);
                session.setMaxInactiveInterval(manager.getMaxInactiveInterval());
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

            log.error("find session失败", e);
            throw new IOException("error fetch session from redis");
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }


    }

    /**
     * 从redis删除session
     *
     * @param session
     */
    public void remove(Session session) {

        Jedis jedis = null;

        log.trace("Removing session ID : " + session.getId());

        try {
            jedis = acquireConnection();
            jedis.del(session.getId());
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    public int getDatabase() {

        return database;
    }

    public void setDatabase(int database) {

        this.database = database;
    }

    public static void main(String[] args) throws Exception {

        RedisRepository redisRepository = new RedisRepository(new ClusterSerializer(), "localhost", 6379, 300, "", 0);
        Jedis           jedis           = redisRepository.acquireConnection();
        jedis.set("a".getBytes(), "b".getBytes());
        String b = new String(jedis.get("a".getBytes()));
        System.out.println(b);
    }
}
