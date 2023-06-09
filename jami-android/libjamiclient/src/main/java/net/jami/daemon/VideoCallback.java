/* ----------------------------------------------------------------------------
 * This file was automatically generated by SWIG (http://www.swig.org).
 * Version 4.0.2
 *
 * Do not make changes to this file unless you know what you are doing--modify
 * the SWIG interface file instead.
 * ----------------------------------------------------------------------------- */

package net.jami.daemon;

public class VideoCallback {
  private transient long swigCPtr;
  protected transient boolean swigCMemOwn;

  protected VideoCallback(long cPtr, boolean cMemoryOwn) {
    swigCMemOwn = cMemoryOwn;
    swigCPtr = cPtr;
  }

  protected static long getCPtr(VideoCallback obj) {
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
        JamiServiceJNI.delete_VideoCallback(swigCPtr);
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
    JamiServiceJNI.VideoCallback_change_ownership(this, swigCPtr, false);
  }

  public void swigTakeOwnership() {
    swigCMemOwn = true;
    JamiServiceJNI.VideoCallback_change_ownership(this, swigCPtr, true);
  }

  public void getCameraInfo(String device, IntVect formats, UintVect sizes, UintVect rates) {
    if (getClass() == VideoCallback.class) JamiServiceJNI.VideoCallback_getCameraInfo(swigCPtr, this, device, IntVect.getCPtr(formats), formats, UintVect.getCPtr(sizes), sizes, UintVect.getCPtr(rates), rates); else JamiServiceJNI.VideoCallback_getCameraInfoSwigExplicitVideoCallback(swigCPtr, this, device, IntVect.getCPtr(formats), formats, UintVect.getCPtr(sizes), sizes, UintVect.getCPtr(rates), rates);
  }

  public void setParameters(String arg0, int format, int width, int height, int rate) {
    if (getClass() == VideoCallback.class) JamiServiceJNI.VideoCallback_setParameters(swigCPtr, this, arg0, format, width, height, rate); else JamiServiceJNI.VideoCallback_setParametersSwigExplicitVideoCallback(swigCPtr, this, arg0, format, width, height, rate);
  }

  public void setBitrate(String arg0, int bitrate) {
    if (getClass() == VideoCallback.class) JamiServiceJNI.VideoCallback_setBitrate(swigCPtr, this, arg0, bitrate); else JamiServiceJNI.VideoCallback_setBitrateSwigExplicitVideoCallback(swigCPtr, this, arg0, bitrate);
  }

  public void requestKeyFrame(String camid) {
    if (getClass() == VideoCallback.class) JamiServiceJNI.VideoCallback_requestKeyFrame(swigCPtr, this, camid); else JamiServiceJNI.VideoCallback_requestKeyFrameSwigExplicitVideoCallback(swigCPtr, this, camid);
  }

  public void startCapture(String camid) {
    if (getClass() == VideoCallback.class) JamiServiceJNI.VideoCallback_startCapture(swigCPtr, this, camid); else JamiServiceJNI.VideoCallback_startCaptureSwigExplicitVideoCallback(swigCPtr, this, camid);
  }

  public void stopCapture(String camid) {
    if (getClass() == VideoCallback.class) JamiServiceJNI.VideoCallback_stopCapture(swigCPtr, this, camid); else JamiServiceJNI.VideoCallback_stopCaptureSwigExplicitVideoCallback(swigCPtr, this, camid);
  }

  public void decodingStarted(String id, String shm_path, int w, int h, boolean is_mixer) {
    if (getClass() == VideoCallback.class) JamiServiceJNI.VideoCallback_decodingStarted(swigCPtr, this, id, shm_path, w, h, is_mixer); else JamiServiceJNI.VideoCallback_decodingStartedSwigExplicitVideoCallback(swigCPtr, this, id, shm_path, w, h, is_mixer);
  }

  public void decodingStopped(String id, String shm_path, boolean is_mixer) {
    if (getClass() == VideoCallback.class) JamiServiceJNI.VideoCallback_decodingStopped(swigCPtr, this, id, shm_path, is_mixer); else JamiServiceJNI.VideoCallback_decodingStoppedSwigExplicitVideoCallback(swigCPtr, this, id, shm_path, is_mixer);
  }

  public VideoCallback() {
    this(JamiServiceJNI.new_VideoCallback(), true);
    JamiServiceJNI.VideoCallback_director_connect(this, swigCPtr, true, true);
  }

}
