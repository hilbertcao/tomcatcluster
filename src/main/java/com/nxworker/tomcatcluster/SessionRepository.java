package com.nxworker.tomcatcluster;

import org.apache.catalina.Manager;
import org.apache.catalina.Session;

import java.io.IOException;

/**
 * session存储
 */
public interface SessionRepository {

    public void save(Session session);

    /**
     * 这里要传manager参数，主要就是为了反序列化session的时候，把manager设置进去，session里面有manager,为的是session里面内容有变更时，可以通过manager做保存操作
     *
     * @param id
     * @param manager
     * @return
     * @throws IOException
     */
    public Session find(String id, Manager manager) throws IOException;

    public void remove(Session session);

    public void close();

    public void expire(Session session, int seconds);
}
