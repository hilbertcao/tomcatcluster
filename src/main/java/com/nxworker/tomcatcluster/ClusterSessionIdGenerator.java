package com.nxworker.tomcatcluster;

import org.apache.catalina.SessionIdGenerator;

/**
 * 自定义sessionId生成器
 */
public class ClusterSessionIdGenerator implements SessionIdGenerator {

    private String jvmRoute;
    private int    sessionIdLength;

    @Override
    public String getJvmRoute() {

        return jvmRoute;
    }

    @Override
    public void setJvmRoute(String jvmRoute) {

        this.jvmRoute = jvmRoute;
    }

    @Override
    public int getSessionIdLength() {

        return sessionIdLength;
    }

    @Override
    public void setSessionIdLength(int sessionIdLength) {

        this.sessionIdLength = sessionIdLength;
    }

    @Override
    public String generateSessionId() {

        return IdGenerator.getUUIDString();
    }

    @Override
    public String generateSessionId(String route) {

        return IdGenerator.getUUIDString();
    }

}
