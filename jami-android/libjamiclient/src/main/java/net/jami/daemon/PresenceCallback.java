/* ----------------------------------------------------------------------------
 * This file was automatically generated by SWIG (http://www.swig.org).
 * Version 4.0.2
 *
 * Do not make changes to this file unless you know what you are doing--modify
 * the SWIG interface file instead.
 * ----------------------------------------------------------------------------- */

package net.jami.daemon;

public class PresenceCallback {
  private transient long swigCPtr;
  protected transient boolean swigCMemOwn;

  protected PresenceCallback(long cPtr, boolean cMemoryOwn) {
    swigCMemOwn = cMemoryOwn;
    swigCPtr = cPtr;
  }

  protected static long getCPtr(PresenceCallback obj) {
    return (obj == null) ? 0 : obj.swigCPtr;
  }

  @SuppressWarnings("deprecation")
  protected void finalize() {
    delete();
  }

  public synchronized void delete() {
    if (swigCPtr != 0) {
      if (swigCMemOwn) {
        swigCMemOwn = false;
        JamiServiceJNI.delete_PresenceCallback(swigCPtr);
      }
      swigCPtr = 0;
    }
  }

  protected void swigDirectorDisconnect() {
    swigCMemOwn = false;
    delete();
  }

  public void swigReleaseOwnership() {
    swigCMemOwn = false;
    JamiServiceJNI.PresenceCallback_change_ownership(this, swigCPtr, false);
  }

  public void swigTakeOwnership() {
    swigCMemOwn = true;
    JamiServiceJNI.PresenceCallback_change_ownership(this, swigCPtr, true);
  }

  public void newServerSubscriptionRequest(String arg0) {
    if (getClass() == PresenceCallback.class) JamiServiceJNI.PresenceCallback_newServerSubscriptionRequest(swigCPtr, this, arg0); else JamiServiceJNI.PresenceCallback_newServerSubscriptionRequestSwigExplicitPresenceCallback(swigCPtr, this, arg0);
  }

  public void serverError(String arg0, String arg1, String arg2) {
    if (getClass() == PresenceCallback.class) JamiServiceJNI.PresenceCallback_serverError(swigCPtr, this, arg0, arg1, arg2); else JamiServiceJNI.PresenceCallback_serverErrorSwigExplicitPresenceCallback(swigCPtr, this, arg0, arg1, arg2);
  }

  public void newBuddyNotification(String arg0, String arg1, int arg2, String arg3) {
    if (getClass() == PresenceCallback.class) JamiServiceJNI.PresenceCallback_newBuddyNotification(swigCPtr, this, arg0, arg1, arg2, arg3); else JamiServiceJNI.PresenceCallback_newBuddyNotificationSwigExplicitPresenceCallback(swigCPtr, this, arg0, arg1, arg2, arg3);
  }

  public void nearbyPeerNotification(String arg0, String arg1, int arg2, String arg3) {
    if (getClass() == PresenceCallback.class) JamiServiceJNI.PresenceCallback_nearbyPeerNotification(swigCPtr, this, arg0, arg1, arg2, arg3); else JamiServiceJNI.PresenceCallback_nearbyPeerNotificationSwigExplicitPresenceCallback(swigCPtr, this, arg0, arg1, arg2, arg3);
  }

  public void subscriptionStateChanged(String arg0, String arg1, int arg2) {
    if (getClass() == PresenceCallback.class) JamiServiceJNI.PresenceCallback_subscriptionStateChanged(swigCPtr, this, arg0, arg1, arg2); else JamiServiceJNI.PresenceCallback_subscriptionStateChangedSwigExplicitPresenceCallback(swigCPtr, this, arg0, arg1, arg2);
  }

  public PresenceCallback() {
    this(JamiServiceJNI.new_PresenceCallback(), true);
    JamiServiceJNI.PresenceCallback_director_connect(this, swigCPtr, true, true);
  }

}
