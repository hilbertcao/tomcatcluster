package com.nxworker.tomcatcluster;

import org.apache.catalina.Manager;
import org.apache.catalina.Session;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

import javax.servlet.ServletException;
import java.io.IOException;


public class ClusterSessionValve extends ValveBase {

    private final Log log = LogFactory.getLog(ClusterSessionValve.class);
    private Manager manager;

    public void setSessionManager(Manager manager) {

        this.manager = manager;
    }

    @Override
    public void invoke(Request request, Response response) throws IOException, ServletException {

        try {
            getNext().invoke(request, response);
        } finally {
            final Session asession = request.getSessionInternal(false);
            storeOrRemoveSession(asession);
        }
    }

    private void storeOrRemoveSession(Session session) {

        try {
            if (session != null) {
                if (session.isValid()) {
                    log.trace("Request with session completed, saving session " + session.getId());
                    if (session.getSession() != null) {
                        log.trace("HTTP Session present, saving " + session.getId());
                        manager.add(session);
                    } else {
                        log.trace("No HTTP Session present, Not saving " + session.getId());
                    }
                } else {
                    log.trace("HTTP Session has been invalidated, removing :" + session.getId());
                    manager.remove(session);
                }
            }
        } catch (Exception e) {
            // Do nothing.
        }
    }
}
