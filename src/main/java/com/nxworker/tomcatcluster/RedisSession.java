package com.nxworker.tomcatcluster;

import org.apache.catalina.Manager;
import org.apache.catalina.session.StandardSession;

import java.security.Principal;
import java.util.HashMap;
import java.util.logging.Logger;


public class RedisSession extends StandardSession {
  private static Logger log = Logger.getLogger("com.lm.b2c.tomcatcluster.RedisSession");

  protected static Boolean manualDirtyTrackingSupportEnabled = false;

  public void setManualDirtyTrackingSupportEnabled(Boolean enabled) {
    manualDirtyTrackingSupportEnabled = enabled;
  }

  protected static String manualDirtyTrackingAttributeKey = "__changed__";

  public void setManualDirtyTrackingAttributeKey(String key) {
    manualDirtyTrackingAttributeKey = key;
  }


  protected HashMap<String, Object> changedAttributes;
  protected Boolean dirty;

  public RedisSession(Manager manager) {
    super(manager);
    resetDirtyTracking();
  }

  public void invalidate() {
    super.invalidate();
    manager.remove(this);
  }

  public Boolean isDirty() {
    return dirty || !changedAttributes.isEmpty();
  }

  public HashMap<String, Object> getChangedAttributes() {
    return changedAttributes;
  }

  public void resetDirtyTracking() {
    changedAttributes = new HashMap<String, Object>();
    dirty = false;
  }

  public void setAttribute(String key, Object value) {
    if (manualDirtyTrackingSupportEnabled && manualDirtyTrackingAttributeKey.equals(key)) {
      dirty = true;
      return;
    }

    Object oldValue = getAttribute(key);
    if ( value == null && oldValue != null
         || oldValue == null && value != null
         || !value.getClass().isInstance(oldValue)
         || !value.equals(oldValue) ) {
      changedAttributes.put(key, value);
    }

    super.setAttribute(key, value);
  }

  public void removeAttribute(String name) {
    dirty = true;
    super.removeAttribute(name);
  }

  @Override
  public void setId(String id) {
    // Specifically do not call super(): it's implementation does unexpected things
    // like calling manager.remove(session.id) and manager.add(session).
    
    this.id = id;
  }

  public void setPrincipal(Principal principal) {
    dirty = true;
    super.setPrincipal(principal);
  }

}