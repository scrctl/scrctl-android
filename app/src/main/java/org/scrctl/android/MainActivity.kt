package org.scrctl.android

import android.content.Context
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator

class MainActivity : AppCompatActivity() {
    private lateinit var controller: SessionController
    private lateinit var root: FrameLayout
    private lateinit var listPage: LinearLayout
    private lateinit var mirrorPage: FrameLayout
    private lateinit var deviceList: LinearLayout
    private lateinit var mirror: MirrorView
    private lateinit var status: TextView
    private lateinit var pin: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var pair: MaterialButton
    private lateinit var refresh: MaterialButton
    private lateinit var prepare: MaterialButton
    private lateinit var connect: MaterialButton
    private lateinit var cancel: MaterialButton
    private var devices = emptyList<Device>()
    private var selected = ""
    private var state = UiState("Starting…", true)
    private val main = Handler(Looper.getMainLooper())
    private var started = false
    private var networkKey = ""
    private var multicast: WifiManager.MulticastLock? = null
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private val networkUpdate = Runnable {
        if (started) {
            val network = connectivity.activeNetwork
            val properties = network?.let(connectivity::getLinkProperties)
            val key = "${network?.networkHandle}:${properties?.interfaceName}:${properties?.linkAddresses}"
            if (key != networkKey) { networkKey = key; mirror.clear(); controller.networkChanged(network != null) }
        }
    }
    private val networks = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = changed()
        override fun onLost(network: Network) = changed()
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = changed()
        private fun changed() { main.removeCallbacks(networkUpdate); main.postDelayed(networkUpdate, 400) }
    }
    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
    private fun row(parent: LinearLayout) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        parent.addView(this, LinearLayout.LayoutParams(-1, -2))
    }
    private fun button(parent: LinearLayout, label: String, action: () -> Unit) = MaterialButton(this).apply {
        text = label; isAllCaps = false; setOnClickListener { action() }
        parent.addView(this, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        root = FrameLayout(this)
        listPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listPage, FrameLayout.LayoutParams(-1, -1))
        ViewCompat.setOnApplyWindowInsetsListener(listPage) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        listPage.addView(MaterialToolbar(this).apply { title = "scrctl"; subtitle = "Connect to an iPhone over Wi-Fi" })
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(16)) }
        scroll.addView(content)
        listPage.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val top = row(content)
        refresh = button(top, "Refresh") { mirror.clear(); controller.refresh() }
        pair = button(top, "Wi-Fi pair") { controller.pair() }
        status = TextView(this).apply { textSize = 16f; setPadding(0, dp(16), 0, dp(12)); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        content.addView(status)
        pin = TextView(this).apply { textSize = 36f; typeface = Typeface.MONOSPACE; textAlignment = View.TEXT_ALIGNMENT_CENTER }
        content.addView(pin)
        progress = LinearProgressIndicator(this).apply { isIndeterminate = true }
        content.addView(progress, LinearLayout.LayoutParams(-1, dp(4)))
        cancel = button(row(content), "Cancel") { controller.cancel() }
        deviceList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(deviceList)
        val actions = row(content)
        prepare = button(actions, "Prepare DDI") { controller.prepare(selected) }
        connect = button(actions, "Connect") { controller.mirror(selected) }
        mirrorPage = FrameLayout(this).apply { visibility = View.GONE }
        mirror = MirrorView(this)
        mirrorPage.addView(mirror, FrameLayout.LayoutParams(-1, -1))
        val back = MaterialButton(this).apply { text = "‹"; contentDescription = "Back to devices"; setOnClickListener { disconnect() } }
        mirrorPage.addView(back, FrameLayout.LayoutParams(dp(56), dp(48), Gravity.TOP or Gravity.START).apply { setMargins(dp(12), dp(12), dp(12), 0) })
        val menu = MaterialButton(this).apply { text = "⋮"; contentDescription = "iPhone controls" }
        menu.setOnClickListener {
            PopupMenu(this, menu).apply {
                listOf("Home", "Volume down", "Volume up", "Lock", "Disconnect").forEachIndexed { index, label -> this.menu.add(0, index, index, label) }
                setOnMenuItemClickListener { item ->
                    when (item.itemId) { 0 -> controller.button(0x40); 1 -> controller.button(0xEA); 2 -> controller.button(0xE9); 3 -> controller.button(0x30); 4 -> disconnect() }
                    true
                }
            }.show()
        }
        mirrorPage.addView(menu, FrameLayout.LayoutParams(dp(56), dp(48), Gravity.TOP or Gravity.END).apply { setMargins(dp(12), dp(12), dp(12), 0) })
        root.addView(mirrorPage, FrameLayout.LayoutParams(-1, -1))
        controller = SessionController(applicationContext, filesDir, cacheDir, { render(it) }, { mirror.submit(it) })
        mirror.acceptsFrame = controller::acceptsFrame; mirror.onTouch = controller::touch
        onBackPressedDispatcher.addCallback(this) { if (state.mirroring) disconnect() else finish() }
        setContentView(root); render(state)
    }
    private fun showMirror(show: Boolean) {
        listPage.visibility = if (show) View.GONE else View.VISIBLE
        mirrorPage.visibility = if (show) View.VISIBLE else View.GONE
        mirror.keepScreenOn = show
        WindowInsetsControllerCompat(window, root).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (show) hide(WindowInsetsCompat.Type.systemBars()) else show(WindowInsetsCompat.Type.systemBars())
        }
    }
    private fun disconnect() { mirror.clear(); showMirror(false); controller.disconnect() }
    private fun renderDevices() {
        deviceList.removeAllViews()
        devices.forEach { device ->
            val card = MaterialCardView(this).apply {
                isCheckable = true; isChecked = device.udid.isNotEmpty() && device.udid == selected
                strokeWidth = if (isChecked) dp(2) else 0
                setContentPadding(dp(16), dp(14), dp(16), dp(14))
            }
            val pairing = if (device.pairing == "record_present") "Paired" else "Not paired"
            val connection = if (device.connection == "connected") "Connected" else "Not connected"
            card.addView(TextView(this).apply {
                textSize = 16f
                text = "${device.name.ifEmpty { "iPhone" }}\n$pairing · $connection\n${device.udid.ifEmpty { "Start Wi-Fi pairing to identify this device" }}"
            })
            card.isEnabled = !state.busy
            card.setOnClickListener { if (!state.busy) { selected = device.udid; render(state) } }
            deviceList.addView(card, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(8), 0, dp(8)) })
        }
    }
    private fun render(next: UiState) {
        if (isDestroyed) return
        val changed = state.mirroring != next.mirroring
        state = next
        next.devices?.let { devices = it; if (devices.none { d -> d.udid == selected }) selected = "" }
        status.text = next.message
        pin.text = next.pin.orEmpty(); pin.visibility = if (next.pin == null) View.GONE else View.VISIBLE
        progress.visibility = if (next.busy) View.VISIBLE else View.GONE
        cancel.visibility = if (next.busy) View.VISIBLE else View.GONE
        refresh.isEnabled = !next.busy; pair.isEnabled = !next.busy
        prepare.isEnabled = !next.busy && selected.isNotEmpty()
        connect.isEnabled = !next.busy && selected.isNotEmpty()
        renderDevices()
        if (changed || !next.mirroring) showMirror(next.mirroring)
    }
    override fun onStart() {
        super.onStart(); started = true
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicast = wifi.createMulticastLock("scrctl-mdns").apply { setReferenceCounted(false); acquire() }
        networkKey = ""; connectivity.registerDefaultNetworkCallback(networks); controller.resume()
    }
    override fun onStop() {
        started = false; main.removeCallbacks(networkUpdate); connectivity.unregisterNetworkCallback(networks)
        mirror.clear(); controller.pause(); showMirror(false)
        multicast?.let { if (it.isHeld) it.release() }; multicast = null
        pin.text = ""; pin.visibility = View.GONE
        super.onStop()
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (!hasFocus && ::mirror.isInitialized) mirror.releaseTouch() }
    override fun onDestroy() { controller.close(); super.onDestroy() }
}
