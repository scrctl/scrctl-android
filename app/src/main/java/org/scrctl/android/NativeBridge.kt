package org.scrctl.android

import android.graphics.Bitmap
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class NativeReply(val json: String, val bitmap: Bitmap?)
fun interface NativeEvents { fun onEvent(json: String) }
object NativeBridge {
    init { System.loadLibrary("scrctl_jni") }
    // Android certificate filenames use the old subject hash. Export the platform
    // TrustManager roots as PEM for the unchanged SDK's OpenSSL-based downloader.
    @Synchronized fun start(directory: String): Long {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        val roots = factory.trustManagers.filterIsInstance<X509TrustManager>()
            .flatMap { it.acceptedIssuers.toList() }.distinctBy { Base64.encodeToString(it.encoded, Base64.NO_WRAP) }
        check(roots.isNotEmpty()) { "Android system trust store is empty" }
        val parent = File(directory).parentFile ?: error("Missing private directory")
        parent.mkdirs()
        val bundle = File(parent, "android-system-roots.pem")
        val temporary = File.createTempFile("system-roots-", ".pem", parent)
        try {
            temporary.bufferedWriter().use { out ->
                roots.forEach { cert ->
                    out.write("-----BEGIN CERTIFICATE-----\n")
                    out.write(Base64.encodeToString(cert.encoded, Base64.NO_WRAP).chunked(64).joinToString("\n"))
                    out.write("\n-----END CERTIFICATE-----\n")
                }
            }
            check(temporary.renameTo(bundle)) { "Cannot prepare Android system trust store" }
        } finally { temporary.delete() }
        return startNative(directory, bundle.absolutePath)
    }
    private external fun startNative(directory: String, caFile: String): Long
    external fun port(runtime: Long): Int
    external fun connect(runtime: Long, events: NativeEvents): Long
    external fun request(runtime: Long, client: Long, method: String, params: String): NativeReply
    external fun interrupt(runtime: Long, client: Long)
    external fun interruptAll(runtime: Long)
    external fun close(runtime: Long, client: Long)
    external fun stop(runtime: Long)
}
