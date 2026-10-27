package org.scrctl.android

import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Explicit opt-in: removes the phone's DDI, cancels a download, then reinstalls. */
@RunWith(AndroidJUnit4::class)
class DdiLifecycleTest {
    private fun report(message: String) {
        println(message)
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
            putString("stream", message + "\n")
        })
    }

    @Test fun inspectAfterUncertainInstallation() {
        val args = InstrumentationRegistry.getArguments()
        val udid = args.getString("xdbHardwareUdid") ?: ""
        assumeTrue("explicit read-only inspection", args.getString("xdbDdiInspect") == "true" && udid.isNotEmpty())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = NativeBridge.start(context, File(context.filesDir, "xdb").absolutePath)
        val client = NativeBridge.connect(runtime, NativeEvents {})
        try {
            val response = NativeBridge.request(runtime, client, "ddi.status",
                JSONObject().put("udid", udid).put("wifi", "auto").toString())
            val envelope = JSONObject(response.json)
            assertTrue(envelope.optJSONObject("error")?.optString("message") ?: "DDI status", envelope.getBoolean("ok"))
            val state = envelope.getJSONObject("result")
            report("DDI inspection: mounted=${state.getBoolean("mounted")} installed=${state.opt("cryptex_installed")} version=${state.optString("cryptex_version")}")
        } finally { NativeBridge.close(runtime, client); NativeBridge.stop(runtime) }
    }

    @Test fun freshInstallAfterCancelledDownload() {
        val arguments = InstrumentationRegistry.getArguments()
        val udid = arguments.getString("xdbHardwareUdid") ?: ""
        assumeTrue("destructive DDI acceptance requires explicit opt-in",
            arguments.getString("xdbDdiLifecycle") == "true" && udid.isNotEmpty())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.filesDir, "xdb")
        val cache = File(context.cacheDir, "ddi-validation-${UUID.randomUUID()}")
        assertTrue(cache.mkdir())
        var runtime = 0L
        var client = 0L
        var cancelDownload = false
        var cancelledAt = 0L
        val progress = Regex("DDI transfer: ([^ ]+) \\((\\d+)/(\\d+) bytes\\)")
        fun open() {
            runtime = NativeBridge.start(context, directory.absolutePath)
            client = NativeBridge.connect(runtime, NativeEvents { raw ->
                val event = JSONObject(raw)
                val message = event.optJSONObject("data")?.optString("message") ?: ""
                if (message.isNotEmpty()) report("DDI lifecycle: ${message.trim()}")
                val transfer = progress.find(message)
                if (cancelDownload && transfer != null) {
                    val bytes = transfer.groupValues[2].toLong()
                    val total = transfer.groupValues[3].toLong()
                    if (bytes > 0 && bytes < total && total > 1024 * 1024) {
                        cancelDownload = false
                        cancelledAt = SystemClock.elapsedRealtime()
                        NativeBridge.interrupt(runtime, client)
                    }
                }
            })
        }
        fun close() {
            if (client != 0L) { NativeBridge.close(runtime, client); client = 0 }
            if (runtime != 0L) { NativeBridge.stop(runtime); runtime = 0 }
        }
        fun params() = JSONObject().put("udid", udid).put("wifi", "auto")
        fun rpc(method: String, parameters: JSONObject = params()): JSONObject {
            val start = SystemClock.elapsedRealtime()
            report("DDI lifecycle: begin $method")
            val envelope = JSONObject(NativeBridge.request(runtime, client, method, parameters.toString()).json)
            report("DDI lifecycle: end $method elapsed_ms=${SystemClock.elapsedRealtime() - start} ok=${envelope.optBoolean("ok")}")
            assertTrue(envelope.optJSONObject("error")?.optString("message") ?: method, envelope.getBoolean("ok"))
            return envelope.getJSONObject("result")
        }
        fun absent() {
            val state = rpc("ddi.status")
            assertFalse("DDI must not be mounted", state.getBoolean("mounted"))
            assertFalse("DDI Cryptex must not be installed", state.getBoolean("cryptex_installed"))
        }
        fun prepare() = params().put("cache_directory", cache.absolutePath)
            .put("download_timeout_seconds", 180).put("install_timeout_seconds", 120)
        var completed = false
        try {
            open()
            rpc("ddi.status")
            rpc("ddi.remove")
            absent()
            assertTrue("unique cache starts empty", cache.listFiles()!!.isEmpty())
            cancelDownload = true
            report("DDI lifecycle: begin cancelled preparation")
            val cancelled = runCatching {
                JSONObject(NativeBridge.request(runtime, client, "ddi.prepare", prepare().toString()).json)
            }
            assertTrue("download progress must trigger cancellation", cancelledAt > 0)
            assertTrue("cancelled preparation cannot succeed", cancelled.isFailure || !cancelled.getOrThrow().getBoolean("ok"))
            close() // Joins the disconnected request and its staging cleanup.
            val cancelMs = SystemClock.elapsedRealtime() - cancelledAt
            report("DDI lifecycle: cancellation_and_cleanup_ms=$cancelMs")
            assertTrue("download cancellation is bounded", cancelMs < 10000)
            assertTrue("cancelled download removes staging files and lock", cache.listFiles()!!.isEmpty())
            open()
            absent()
            assertTrue("retry also starts with an empty cache", cache.listFiles()!!.isEmpty())
            assertTrue(rpc("ddi.prepare", prepare()).getBoolean("prepared"))
            assertTrue("download produced a verified cache", cache.walkTopDown().any { it.isFile && it.name == ".complete" })
            val state = rpc("ddi.status")
            assertTrue(state.getBoolean("mounted"))
            assertTrue(state.getBoolean("cryptex_installed"))
            report("DDI lifecycle: verified installed_version=${state.getString("cryptex_version")}")
            rpc("connection.open", params().put("transport", "wifi"))
            rpc("video.start", JSONObject().put("decode", true).put("hardware_decode", false))
            var serial = 0L
            repeat(3) {
                val until = SystemClock.elapsedRealtime() + 15000
                var accepted = false
                while (!accepted && SystemClock.elapsedRealtime() < until) {
                    val response = NativeBridge.request(runtime, client, "video.frame",
                        JSONObject().put("since", serial).put("timeout_ms", 1000).toString())
                    val envelope = JSONObject(response.json)
                    if (!envelope.getBoolean("ok")) {
                        val code = envelope.getJSONObject("error").getString("code")
                        assertTrue("frame error: $code", code == "no_frame" || code == "geometry_changed")
                        continue
                    }
                    val next = envelope.getJSONObject("result").getLong("serial")
                    assertTrue(next > serial); serial = next
                    val bitmap = response.bitmap ?: error("Missing decoded frame")
                    assertTrue(bitmap.width > 0 && bitmap.height > 0)
                    bitmap.recycle(); accepted = true
                }
                assertTrue("new DDI supports advancing decoded frames", accepted)
            }
            rpc("video.stop", JSONObject()); rpc("connection.close", JSONObject())
            report("DDI lifecycle: complete; DDI left installed and mounted")
            completed = true
        } finally {
            close()
            // Only this test's unique cache is disposable; preserve App data.
            if (completed) cache.deleteRecursively()
        }
    }
}
