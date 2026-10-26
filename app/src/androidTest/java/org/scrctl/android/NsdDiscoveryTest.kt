package org.scrctl.android

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xdevicebridge.NsdDiscovery
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class NsdDiscoveryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun snapshot() = JSONObject(NsdDiscovery.status())
    private fun waitUntil(label: String, test: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!test()) {
            if (System.nanoTime() > deadline) fail("$label: ${NsdDiscovery.status()}")
            Thread.sleep(50)
        }
    }
    @Test fun nativeSubscriptionSharesLifetimeAndEvictsLostServices() {
        assumeTrue(Build.VERSION.SDK_INT >= 34)
        val a = NsdDiscovery.acquire(context)
        val b = NsdDiscovery.acquire(context)
        val manager = context.getSystemService(NsdManager::class.java)
        val name = "xdb-test-${UUID.randomUUID().toString().take(8)}"
        val registered = CountDownLatch(1)
        var registrationError = ""
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) { registered.countDown() }
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) { registrationError = "registration $code"; registered.countDown() }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
        }
        fun hasPeer(): Boolean {
            val list = snapshot().getJSONArray("services")
            return (0 until list.length()).any { list.getJSONObject(it).getString("name") == name }
        }
        val directory = File(context.filesDir, "nsd-test-${UUID.randomUUID()}").apply { mkdirs() }
        var runtime = 0L
        var registrationActive = false
        try {
            waitUntil("NSD started") { snapshot().getBoolean("available") }
            val info = NsdServiceInfo().apply {
                serviceName = name; serviceType = "_remotepairing._tcp."; port = 49091
                setAttribute("identifier", name); setAttribute("name", "NSD test peer")
            }
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            registrationActive = true
            assertTrue(registered.await(10, TimeUnit.SECONDS)); assertEquals("", registrationError)
            waitUntil("service resolved") { hasPeer() }
            runtime = NativeBridge.start(context, directory.absolutePath)
            val client = NativeBridge.connect(runtime, NativeEvents {})
            try {
                val start = System.nanoTime()
                val response = JSONObject(NativeBridge.request(runtime, client, "devices", "{\"transport\":\"wifi\",\"timeout_ms\":3000}").json)
                assertTrue(response.toString(), response.getBoolean("ok"))
                val peers = response.getJSONObject("result").getJSONArray("devices")
                assertTrue("NSD snapshot reached native SDK", (0 until peers.length()).any { i ->
                    val candidates = peers.getJSONObject(i).getJSONArray("candidates")
                    (0 until candidates.length()).any { candidates.getJSONObject(it).getInt("port") == 49091 }
                })
                assertTrue("warm snapshot should not rescan for three seconds", System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2))
            } finally { NativeBridge.close(runtime, client); NativeBridge.stop(runtime); runtime = 0 }
            a.close()
            assertTrue(snapshot().getBoolean("available"))
            manager.unregisterService(listener); registrationActive = false
            waitUntil("lost service removed") { !hasPeer() }
            b.close()
            assertFalse(snapshot().getBoolean("available"))
            assertEquals(0, snapshot().getJSONArray("services").length())
            NsdDiscovery.acquire(context).use {
                waitUntil("new lifecycle starts") { snapshot().getBoolean("available") }
                assertFalse(hasPeer())
            }
        } finally {
            if (runtime != 0L) NativeBridge.stop(runtime)
            if (registrationActive) runCatching { manager.unregisterService(listener) }
            a.close(); b.close(); directory.deleteRecursively()
        }
    }
    @Test fun pairedIPhoneStreamsThroughNativeDiscovery() {
        val udid = InstrumentationRegistry.getArguments().getString("xdbHardwareUdid") ?: ""
        assumeTrue("opt-in hardware acceptance", udid.isNotEmpty() && Build.VERSION.SDK_INT >= 34)
        val directory = File(context.filesDir, "xdb") // Existing App-owned record, never exported/overwritten.
        val runtime = NativeBridge.start(context, directory.absolutePath)
        val client = NativeBridge.connect(runtime, NativeEvents {})
        fun rpc(name: String, params: JSONObject = JSONObject()): Pair<JSONObject, NativeReply> {
            val response = NativeBridge.request(runtime, client, name, params.toString())
            val envelope = JSONObject(response.json)
            assertTrue(envelope.optJSONObject("error")?.optString("message") ?: name, envelope.getBoolean("ok"))
            return envelope.getJSONObject("result") to response
        }
        try {
            waitUntil("native iPhone discovery") { snapshot().getBoolean("available") && snapshot().getJSONArray("services").length() > 0 }
            val devices = rpc("devices", JSONObject().put("transport", "wifi").put("timeout_ms", 3000)).first.getJSONArray("devices")
            assertTrue("existing paired iPhone", (0 until devices.length()).any { devices.getJSONObject(it).optString("udid") == udid && devices.getJSONObject(it).optString("pairing") == "record_present" })
            rpc("connection.open", JSONObject().put("udid", udid).put("transport", "wifi").put("wifi", "auto"))
            rpc("device.info")
            rpc("video.start", JSONObject().put("decode", true).put("hardware_decode", false))
            var serial = 0L
            repeat(3) {
                var accepted = false
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                while (!accepted && System.nanoTime() < deadline) {
                    val response = NativeBridge.request(runtime, client, "video.frame", JSONObject().put("since", serial).put("timeout_ms", 1000).toString())
                    val envelope = JSONObject(response.json)
                    if (!envelope.getBoolean("ok")) {
                        val code = envelope.getJSONObject("error").getString("code")
                        assertTrue("frame error: $code", code == "no_frame" || code == "geometry_changed")
                        continue
                    }
                    val result = envelope.getJSONObject("result")
                    assertTrue(result.getLong("serial") > serial)
                    serial = result.getLong("serial")
                    val bitmap = response.bitmap ?: error("Missing decoded frame")
                    assertTrue(bitmap.width > 0 && bitmap.height > 0)
                    // Do not save the user's screen to the repository/test report.
                    bitmap.recycle(); accepted = true
                }
                assertTrue("advancing decoded frame", accepted)
            }
            rpc("video.stop"); rpc("connection.close")
        } finally { NativeBridge.close(runtime, client); NativeBridge.stop(runtime) }
    }

    @Test fun pairedIPhoneRepeatsDdiStatusWithoutStaleSessionDelay() {
        val udid = InstrumentationRegistry.getArguments().getString("xdbHardwareUdid") ?: ""
        assumeTrue("opt-in prepared iPhone acceptance", udid.isNotEmpty() && Build.VERSION.SDK_INT >= 34)
        val runtime = NativeBridge.start(context, File(context.filesDir, "xdb").absolutePath)
        val client = NativeBridge.connect(runtime, NativeEvents {})
        try {
            waitUntil("native iPhone discovery") { snapshot().getBoolean("available") && snapshot().getJSONArray("services").length() > 0 }
            repeat(3) { iteration ->
                val start = System.nanoTime()
                val params = JSONObject().put("udid", udid).put("wifi", "auto")
                val envelope = JSONObject(NativeBridge.request(runtime, client, "ddi.status", params.toString()).json)
                val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
                // No pairing mutation, image download/removal, or screen contents.
                println("DDI status iteration=$iteration elapsed_ms=$elapsed")
                assertTrue(envelope.optJSONObject("error")?.optString("message") ?: "DDI status", envelope.getBoolean("ok"))
                assertTrue("prepared iPhone required", envelope.getJSONObject("result").getBoolean("mounted"))
                assertTrue("repeated query must not wait for the old 30-second session expiry", elapsed < 10000)
            }
        } finally { NativeBridge.close(runtime, client); NativeBridge.stop(runtime) }
    }
}
