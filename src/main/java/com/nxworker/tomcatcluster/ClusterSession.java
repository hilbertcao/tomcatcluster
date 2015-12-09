package com.nxworker.tomcatcluster;

import org.apache.catalina.Manager;
import org.apache.catalina.session.StandardSession;

import java.security.Principal;
import java.util.HashMap;
import java.util.logging.Logger;


public class ClusterSession extends StandardSession {

    private static Logger log = Logger.getLogger("com.nxworker.tomcatcluster.ClusterSession");

    protected static Boolean manualDirtyTrackingSupportEnabled = false;

    public void setManualDirtyTrackingSupportEnabled(Boolean enabled) {

        manualDirtyTrackingSupportEnabled = enabled;
    }

    protected static String manualDirtyTrackingAttributeKey = "__changed__";

    public void setManualDirtyTrackingAttributeKey(String key) {

        manualDirtyTrackingAttributeKey = key;
    }


    protected HashMap<String, Object> changedAttributes;
    protected Boolean                 dirty;

    public ClusterSession(Manager manager) {

        super(manager);
        resetDirtyTracking();
    }

    public void invalidate() {

        super.invalidate();
    }

    public Boolean isDirty() {

        return dirty || !changedAttributes.isEmpty();
    }

    public HashMap<String, Object> getChangedAttributes() {

        return changedAttributes;
    }

    /**
     * 重置为非脏数据
     */
    public void resetDirtyTracking() {

        changedAttributes = new HashMap<String, Object>();
        dirty = false;
    }

    public void setAttribute(String key, Object value) {

        log.info("setAttribute" + key + ":" + value);

        //先判断是不是手动置为脏数据
        if (manualDirtyTrackingSupportEnabled && manualDirtyTrackingAttributeKey.equals(key)) {
            dirty = true;
            return;
        }

        //session里面的值如果修改,就放到脏键值里面
        Object oldValue = getAttribute(key);
        if ((value == null && oldValue != null)
                || (oldValue == null && value != null)
                || (value != null && !value.getClass().isInstance(oldValue))
                || (value != null && !value.equals(oldValue))) {

            changedAttributes.put(key, value);
        }

        super.setAttribute(key, value);

        //如果有做修改，就保存这个session
        if (isDirty()) {

            manager.add(this);
        }

    }

    public void removeAttribute(String name) {

        super.removeAttribute(name);

        log.info("remove attribute" + name);
        dirty = true;
        //如果有做修改，就保存这个session
        manager.add(this);

    }

    @Override
    public void setId(String id) {

        this.id = id;
    }

    public void setPrincipal(Principal principal) {

        log.info("setPrincipal");
        //标记下已经修改，已经是脏数据
        dirty = true;
        super.setPrincipal(principal);
    }

    @Override
    public String toString() {

        StringBuilder sb = new StringBuilder();
        sb.append("ClusterSession[");
        sb.append(id);
        sb.append("]");
        return (sb.toString());
    }

}
