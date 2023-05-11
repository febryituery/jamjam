/* ----------------------------------------------------------------------------
 * This file was automatically generated by SWIG (http://www.swig.org).
 * Version 4.0.2
 *
 * Do not make changes to this file unless you know what you are doing--modify
 * the SWIG interface file instead.
 * ----------------------------------------------------------------------------- */

package net.jami.daemon;

public class DataTransferCallback {
  private transient long swigCPtr;
  protected transient boolean swigCMemOwn;

  protected DataTransferCallback(long cPtr, boolean cMemoryOwn) {
    swigCMemOwn = cMemoryOwn;
    swigCPtr = cPtr;
  }

  protected static long getCPtr(DataTransferCallback obj) {
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
        JamiServiceJNI.delete_DataTransferCallback(swigCPtr);
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
    JamiServiceJNI.DataTransferCallback_change_ownership(this, swigCPtr, false);
  }

  public void swigTakeOwnership() {
    swigCMemOwn = true;
    JamiServiceJNI.DataTransferCallback_change_ownership(this, swigCPtr, true);
  }

  public void dataTransferEvent(String accountId, String conversationId, String interactionId, String fileId, int eventCode) {
    if (getClass() == DataTransferCallback.class) JamiServiceJNI.DataTransferCallback_dataTransferEvent(swigCPtr, this, accountId, conversationId, interactionId, fileId, eventCode); else JamiServiceJNI.DataTransferCallback_dataTransferEventSwigExplicitDataTransferCallback(swigCPtr, this, accountId, conversationId, interactionId, fileId, eventCode);
  }

  public DataTransferCallback() {
    this(JamiServiceJNI.new_DataTransferCallback(), true);
    JamiServiceJNI.DataTransferCallback_director_connect(this, swigCPtr, true, true);
  }

}
