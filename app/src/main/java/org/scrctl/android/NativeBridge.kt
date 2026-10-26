package org.scrctl.android

import android.graphics.Bitmap

class NativeReply(val json: String, val bitmap: Bitmap?)
fun interface NativeEvents { fun onEvent(json: String) }
object NativeBridge {
    init { System.loadLibrary("scrctl_jni") }
    external fun start(directory: String): Long
    external fun port(runtime: Long): Int
    external fun connect(runtime: Long, events: NativeEvents): Long
    external fun request(runtime: Long, client: Long, method: String, params: String): NativeReply
    external fun interrupt(runtime: Long, client: Long)
    external fun interruptAll(runtime: Long)
    external fun close(runtime: Long, client: Long)
    external fun stop(runtime: Long)
}
