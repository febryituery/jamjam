/* ----------------------------------------------------------------------------
 * This file was automatically generated by SWIG (http://www.swig.org).
 * Version 4.0.2
 *
 * Do not make changes to this file unless you know what you are doing--modify
 * the SWIG interface file instead.
 * ----------------------------------------------------------------------------- */

package net.jami.daemon;

public class Blob extends java.util.AbstractList<Byte> implements java.util.RandomAccess {
  private transient long swigCPtr;
  protected transient boolean swigCMemOwn;

  protected Blob(long cPtr, boolean cMemoryOwn) {
    swigCMemOwn = cMemoryOwn;
    swigCPtr = cPtr;
  }

  protected static long getCPtr(Blob obj) {
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
        JamiServiceJNI.delete_Blob(swigCPtr);
      }
      swigCPtr = 0;
    }
  }

  public static byte[] bytesFromString(String in) {
    try {
      return in.getBytes("UTF-8");
    } catch (java.io.UnsupportedEncodingException e) {
      return in.getBytes();
    }
  }
  public static Blob fromString(String in) {
    Blob n = new Blob();
    n.setBytes(bytesFromString(in));
    return n;
  }

  public Blob(byte[] initialElements) {
    this();
    reserve(initialElements.length);

    for (byte element : initialElements) {
      add(element);
    }
  }

  public Blob(Iterable<Byte> initialElements) {
    this();
    for (byte element : initialElements) {
      add(element);
    }
  }

  public Byte get(int index) {
    return doGet(index);
  }

  public Byte set(int index, Byte e) {
    return doSet(index, e);
  }

  public boolean add(Byte e) {
    modCount++;
    doAdd(e);
    return true;
  }

  public void add(int index, Byte e) {
    modCount++;
    doAdd(index, e);
  }

  public Byte remove(int index) {
    modCount++;
    return doRemove(index);
  }

  protected void removeRange(int fromIndex, int toIndex) {
    modCount++;
    doRemoveRange(fromIndex, toIndex);
  }

  public int size() {
    return doSize();
  }

  public Blob() {
    this(JamiServiceJNI.new_Blob__SWIG_0(), true);
  }

  public Blob(Blob other) {
    this(JamiServiceJNI.new_Blob__SWIG_1(Blob.getCPtr(other), other), true);
  }

  public long capacity() {
    return JamiServiceJNI.Blob_capacity(swigCPtr, this);
  }

  public void reserve(long n) {
    JamiServiceJNI.Blob_reserve(swigCPtr, this, n);
  }

  public boolean isEmpty() {
    return JamiServiceJNI.Blob_isEmpty(swigCPtr, this);
  }

  public void clear() {
    JamiServiceJNI.Blob_clear(swigCPtr, this);
  }

  public Blob(int count, byte value) {
    this(JamiServiceJNI.new_Blob__SWIG_2(count, value), true);
  }

  private int doSize() {
    return JamiServiceJNI.Blob_doSize(swigCPtr, this);
  }

  private void doAdd(byte x) {
    JamiServiceJNI.Blob_doAdd__SWIG_0(swigCPtr, this, x);
  }

  private void doAdd(int index, byte x) {
    JamiServiceJNI.Blob_doAdd__SWIG_1(swigCPtr, this, index, x);
  }

  private byte doRemove(int index) {
    return JamiServiceJNI.Blob_doRemove(swigCPtr, this, index);
  }

  private byte doGet(int index) {
    return JamiServiceJNI.Blob_doGet(swigCPtr, this, index);
  }

  private byte doSet(int index, byte val) {
    return JamiServiceJNI.Blob_doSet(swigCPtr, this, index, val);
  }

  private void doRemoveRange(int fromIndex, int toIndex) {
    JamiServiceJNI.Blob_doRemoveRange(swigCPtr, this, fromIndex, toIndex);
  }

  public byte[] getBytes() {
  return JamiServiceJNI.Blob_getBytes(swigCPtr, this);
}

  public void setBytes(byte[] data) {
    JamiServiceJNI.Blob_setBytes(swigCPtr, this, data);
  }

}
