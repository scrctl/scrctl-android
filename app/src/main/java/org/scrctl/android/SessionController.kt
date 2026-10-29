package org.scrctl.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class RpcFailure(val code: String, message: String) : Exception(message)
data class Device(val udid: String, val name: String, val pairing: String, val connection: String)
data class UiState(val message: String, val busy: Boolean = false, val mirroring: Boolean = false,
                   val pin: String? = null, val devices: List<Device>? = null)

class SessionController(private val context: Context, private val files: File, private val cache: File,
                        private val update: (UiState) -> Unit, private val frame: (VideoFrame) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val operations = Executors.newSingleThreadExecutor()
    private val video = Executors.newSingleThreadExecutor()
    private val inputs = Executors.newSingleThreadExecutor()
    private val epoch = AtomicLong()
    private val runtime = AtomicLong()
    private val foreground = AtomicBoolean()
    private val cancelled = AtomicBoolean()
    private val requestClient = AtomicLong()
    private var videoTask: Future<*>? = null
    @Volatile private var inputClient = 0L
    @Volatile private var owner = 0L
    @Volatile private var selected = ""
    @Volatile private var wantMirror = false
    @Volatile var running = false; private set
    private val touchLock = Any()
    private data class Touch(val contact: Int, val point: Point, val down: Boolean, val move: Boolean)
    private val touches = java.util.ArrayDeque<Touch>()
    private var inputScheduled = false
    private val buttonPending = AtomicBoolean()

    private fun valid(g: Long) = foreground.get() && g == epoch.get()
    private fun emit(g: Long, state: UiState) { main.post { if (valid(g)) update(state) } }
    private fun events(g: Long): NativeEvents {
        var pin: String? = null
        return NativeEvents { raw ->
        if (valid(g) && !cancelled.get()) runCatching {
            val event = JSONObject(raw)
            val data = event.optJSONObject("data") ?: JSONObject()
            if (event.optString("event") == "pin") {
                pin = data.getString("pin")
                emit(g, UiState("Enter this code on the iPhone", true, pin = pin))
            }
            else {
                val total = data.optLong("total")
                val progress = if (total > 0) "${data.optString("file")} ${data.optLong("bytes") * 100 / total}%" else data.optString("message", "Preparing…")
                emit(g, UiState(progress, true, pin = pin))
            }
        }
    }
    }
    private fun connect(r: Long, g: Long): Long {
        val c = NativeBridge.connect(r, events(g))
        if (!valid(g)) { NativeBridge.close(r, c); throw InterruptedException("Session changed") }
        return c
    }
    internal fun serverPort(): Int = runCatching { NativeBridge.port(runtime.get()) }.getOrDefault(0)
    private fun rpc(r: Long, c: Long, name: String, params: JSONObject = JSONObject()): Pair<JSONObject, NativeReply> {
        val reply = NativeBridge.request(r, c, name, params.toString())
        val envelope = JSONObject(reply.json)
        if (!envelope.optBoolean("ok")) {
            val error = envelope.optJSONObject("error") ?: JSONObject()
            throw RpcFailure(error.optString("code"), error.optString("message", "Request failed"))
        }
        return Pair(envelope.optJSONObject("result") ?: JSONObject(), reply)
    }
    fun acceptsFrame(g: Long) = valid(g) && running
    fun resume() { foreground.set(true); restart("Starting local XDB…") }
    fun networkChanged(available: Boolean) {
        if (!foreground.get()) return
        if (available) restart("Network changed; reconnecting…") else suspendSession("No network. Waiting for connectivity.")
    }
    private fun interrupt() { runtime.get().takeIf { it != 0L }?.let { NativeBridge.interruptAll(it) } }
    private fun stopCurrent() {
        running = false
        synchronized(touchLock) { touches.clear() }
        val r = runtime.getAndSet(0)
        if (r == 0L) return
        NativeBridge.interruptAll(r)
        // These barriers wait for native calls to return, never on the UI thread.
        videoTask?.let { runCatching { it.get(35, TimeUnit.SECONDS) } }; videoTask = null
        runCatching { inputs.submit {}.get(35, TimeUnit.SECONDS) }
        owner = 0; inputClient = 0; requestClient.set(0)
        NativeBridge.stop(r)
    }
    private fun restart(message: String) {
        val g = epoch.incrementAndGet(); interrupt(); emit(g, UiState(message, true))
        operations.execute {
            stopCurrent()
            if (!valid(g)) return@execute
            try {
                val directory = File(files, "xdb").apply { mkdirs() }
                val r = NativeBridge.start(context, directory.absolutePath); runtime.set(r)
                if (!valid(g)) { stopCurrent(); return@execute }
                val c = connect(r, g)
                try { rpc(r, c, "server.info") } finally { NativeBridge.close(r, c) }
                if (wantMirror && selected.isNotEmpty()) beginMirror(r, g, selected) else discover(r, g)
            } catch (e: Exception) { emit(g, UiState(e.message ?: "Start failed")) }
        }
    }
    private fun suspendSession(message: String) {
        val g = epoch.incrementAndGet(); interrupt(); emit(g, UiState(message))
        operations.execute { stopCurrent() }
    }
    fun pause() { foreground.set(false); epoch.incrementAndGet(); interrupt(); operations.execute { stopCurrent() } }
    fun close() {
        pause()
        operations.execute { video.shutdown(); inputs.shutdown(); operations.shutdown() }
    }
    private fun discover(r: Long, g: Long) {
        val c = connect(r, g); requestClient.set(c)
        try {
            val result = rpc(r, c, "devices", JSONObject().put("transport", "wifi").put("timeout_ms", 3000)).first
            val array = result.getJSONArray("devices")
            val list = (0 until array.length()).map { i -> array.getJSONObject(i).let {
                Device(it.optString("udid"), it.optString("name"), it.optString("pairing"), it.optString("connection"))
            } }
            val warnings = result.optJSONArray("warnings")
            emit(g, UiState(if (list.isEmpty()) "No iPhone found. Keep Developer Mode open, or start Wi-Fi pairing." else "Select an iPhone",
                devices = list))
            if (warnings != null && warnings.length() > 0) emit(g, UiState("Discovery: ${warnings.join("; ")}", devices = list))
        } finally { requestClient.compareAndSet(c, 0); NativeBridge.close(r, c) }
    }
    private fun operation(message: String, body: (Long, Long, Long) -> Unit) {
        val g = epoch.get(); cancelled.set(false); emit(g, UiState(message, true))
        operations.execute {
            if (!valid(g)) return@execute
            val r = runtime.get()
            if (r == 0L) { emit(g, UiState("Server not ready; retry discovery")); return@execute }
            var c = 0L
            try {
                c = connect(r, g); requestClient.set(c)
                if (cancelled.get()) throw InterruptedException()
                body(r, g, c)
            } catch (e: Exception) {
                emit(g, UiState(if (cancelled.get()) "Cancelled. Completed device changes are not rolled back." else e.message ?: "Operation failed"))
            } finally { requestClient.compareAndSet(c, 0); if (c != 0L) NativeBridge.close(r, c) }
        }
    }
    fun refresh() {
        wantMirror = false
        if (runtime.get() == 0L) restart("Discovering iPhones…")
        else operation("Discovering iPhones…") { r, g, _ -> discover(r, g) }
    }
    fun pair() = operation("Waiting for iPhone pairing…") { r, g, c ->
        rpc(r, c, "pair", JSONObject().put("transport", "wifi").put("display_brand", "scrctl").put("timeout_ms", 120000))
        if (cancelled.get()) throw InterruptedException()
        discover(r, g)
    }
    fun prepare(udid: String) = operation("Preparing DDI…") { r, g, c ->
        rpc(r, c, "ddi.prepare", JSONObject().put("udid", udid).put("wifi", "auto")
            .put("cache_directory", File(cache, "ddi").apply { mkdirs() }.absolutePath))
        if (cancelled.get()) throw InterruptedException()
        emit(g, UiState("DDI prepared. You can now mirror this iPhone."))
    }
    fun cancel() {
        cancelled.set(true)
        val r = runtime.get(); val c = requestClient.get()
        if (r != 0L && c != 0L) NativeBridge.interrupt(r, c)
    }
    fun mirror(udid: String) { selected = udid; wantMirror = true; restart("Connecting to iPhone…") }
    fun disconnect() { wantMirror = false; restart("Disconnected; discovering devices…") }
    private fun beginMirror(r: Long, g: Long, udid: String) {
        val c = connect(r, g); owner = c; requestClient.set(c)
        try {
            val session = rpc(r, c, "connection.open", JSONObject().put("udid", udid).put("wifi", "auto")).first
            rpc(r, c, "display.info")
            rpc(r, c, "video.start", JSONObject().put("decode", true).put("hardware_decode", false))
            val attachment = session.getString("attachment")
            inputClient = connect(r, g)
            rpc(r, inputClient, "connection.attach", JSONObject().put("attachment", attachment))
            running = true; requestClient.set(0)
            emit(g, UiState("Mirroring • software decode", mirroring = true))
            videoTask = video.submit { receive(r, g, attachment) }
        } catch (e: Exception) { wantMirror = false; stopCurrent(); throw e }
    }
    private fun receive(r: Long, g: Long, attachment: String) {
        var c = 0L
        try {
            c = connect(r, g); rpc(r, c, "connection.attach", JSONObject().put("attachment", attachment))
            var serial = 0L; var statusAt = 0L
            while (valid(g) && running) {
                val started = System.nanoTime()
                if (started >= statusAt) {
                    val status = rpc(r, c, "media.status").first
                    if (status.optString("error").isNotEmpty()) throw IllegalStateException(status.getString("error"))
                    statusAt = started + 1_000_000_000
                }
                try {
                    val (value, reply) = rpc(r, c, "video.frame", JSONObject().put("since", serial).put("timeout_ms", 250))
                    serial = value.getLong("serial")
                    val d = value.optJSONObject("display") ?: JSONObject()
                    val geometry = Geometry(value.getInt("width"), value.getInt("height"), d.optInt("width"), d.optInt("height"), Geometry.rotation(d.optString("orientation")))
                    reply.bitmap?.let { image -> if (valid(g)) frame(VideoFrame(image, geometry, g)) else image.recycle() }
                } catch (e: RpcFailure) { if (e.code != "no_frame" && e.code != "geometry_changed") throw e }
                val remaining = 50 - (System.nanoTime() - started) / 1_000_000
                if (remaining > 0) Thread.sleep(remaining)
            }
        } catch (e: Exception) {
            if (valid(g)) {
                wantMirror = false
                failSession(g, "Disconnected: ${e.message}")
            }
        } finally { if (c != 0L) NativeBridge.close(r, c) }
    }
    fun touch(contact: Int, point: Point, down: Boolean, move: Boolean) {
        if (!running) return
        val r = runtime.get(); val c = inputClient; val g = epoch.get()
        synchronized(touchLock) {
            // Coalesce only within the trailing move batch, never across a down/up.
            if (move) {
                val iterator = touches.descendingIterator()
                while (iterator.hasNext()) {
                    val previous = iterator.next()
                    if (!previous.move) break
                    if (previous.contact == contact) { iterator.remove(); break }
                }
            }
            if (touches.size >= 64) {
                failSession(g, "Input queue is full; disconnected to release held contacts")
                return
            }
            touches.add(Touch(contact, point, down, move))
            if (inputScheduled) return
            inputScheduled = true
        }
        inputs.execute {
            try {
                while (valid(g) && running) {
                    val next = synchronized(touchLock) {
                        if (touches.isEmpty()) { inputScheduled = false; return@execute }
                        touches.removeFirst()
                    }
                    rpc(r, c, "input.touch", JSONObject().put("contact", next.contact).put("x", next.point.x).put("y", next.point.y).put("down", next.down))
                }
            } catch (e: Exception) { if (valid(g)) failSession(g, "Input failed: ${e.message}") }
            synchronized(touchLock) { inputScheduled = false; touches.clear() }
        }
    }
    private fun failSession(g: Long, message: String) {
        if (!epoch.compareAndSet(g, g + 1)) return
        wantMirror = false; running = false; interrupt()
        emit(g + 1, UiState(message))
        operations.execute { stopCurrent() }
    }
    fun button(usage: Int) {
        if (!running || !buttonPending.compareAndSet(false, true)) return
        val r = runtime.get(); val c = inputClient; val g = epoch.get()
        inputs.execute {
            if (!valid(g) || !running) { buttonPending.set(false); return@execute }
            try {
                rpc(r, c, "input.button", JSONObject().put("usage", usage).put("down", true))
                Thread.sleep(90)
                rpc(r, c, "input.button", JSONObject().put("usage", usage).put("down", false))
            } catch (e: Exception) { if (valid(g)) failSession(g, "Button failed: ${e.message}") }
            finally { buttonPending.set(false) }
        }
    }
}
