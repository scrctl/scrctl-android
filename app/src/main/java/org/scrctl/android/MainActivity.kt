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
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout

class MainActivity : AppCompatActivity() {
    private lateinit var controller: SessionController
    private lateinit var mirror: MirrorView
    private lateinit var status: TextView
    private lateinit var pin: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var dropdown: MaterialAutoCompleteTextView
    private lateinit var pair: MaterialButton
    private lateinit var refresh: MaterialButton
    private lateinit var prepare: MaterialButton
    private lateinit var connect: MaterialButton
    private lateinit var cancel: MaterialButton
    private lateinit var disconnect: MaterialButton
    private val deviceButtons = mutableListOf<MaterialButton>()
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
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = changed()
        private fun changed() { main.removeCallbacks(networkUpdate); main.postDelayed(networkUpdate, 400) }
    }
    private fun dp(n: Int) = (resources.displayMetrics.density * n).toInt()
    private fun row(parent: LinearLayout): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        parent.addView(this, LinearLayout.LayoutParams(-1, -2))
    }
    private fun button(parent: LinearLayout, text: String, action: () -> Unit): MaterialButton = MaterialButton(this).apply {
        this.text = text; isAllCaps = false
        setOnClickListener { action() }
        parent.addView(this, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        root.addView(MaterialToolbar(this).apply { title = "scrctl"; subtitle = "iPhone over Wi-Fi" })
        val scroll = ScrollView(this)
        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), dp(8)) }
        scroll.addView(controls)
        root.addView(scroll, LinearLayout.LayoutParams(-1, dp(280)))
        val selector = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedExposedDropdownMenuStyle).apply { hint = "iPhone" }
        dropdown = MaterialAutoCompleteTextView(selector.context).apply { inputType = 0 }
        selector.addView(dropdown, LinearLayout.LayoutParams(-1, -2)); controls.addView(selector)
        dropdown.setOnItemClickListener { _, _, position, _ -> selected = devices[position].udid; render(state) }
        val first = row(controls)
        refresh = button(first, "Refresh") { mirror.clear(); controller.refresh() }
        pair = button(first, "Wi-Fi pair") { controller.pair() }
        val second = row(controls)
        prepare = button(second, "Prepare DDI") { controller.prepare(selected) }
        connect = button(second, "Mirror") { controller.mirror(selected) }
        disconnect = button(second, "Disconnect") { mirror.clear(); controller.disconnect() }
        status = TextView(this).apply { textSize = 14f; setPadding(0, dp(6), 0, dp(6)); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        controls.addView(status)
        pin = TextView(this).apply { textSize = 30f; typeface = Typeface.MONOSPACE; textAlignment = View.TEXT_ALIGNMENT_CENTER }
        controls.addView(pin)
        progress = LinearProgressIndicator(this).apply { isIndeterminate = true }
        controls.addView(progress, LinearLayout.LayoutParams(-1, dp(4)))
        cancel = button(row(controls), "Cancel") { controller.cancel() }
        mirror = MirrorView(this)
        root.addView(mirror, LinearLayout.LayoutParams(-1, 0, 1f))
        val buttons = row(root)
        listOf("Home" to 0x40, "Vol −" to 0xEA, "Vol +" to 0xE9, "Lock" to 0x30).forEach { (label, usage) ->
            deviceButtons += button(buttons, label) { controller.button(usage) }
        }
        controller = SessionController(filesDir, cacheDir, { render(it) }, { mirror.submit(it) })
        mirror.acceptsFrame = controller::acceptsFrame
        mirror.onTouch = controller::touch
        setContentView(root)
        render(state)
    }
    private fun render(next: UiState) {
        if (isDestroyed) return
        state = next
        next.devices?.let { found ->
            devices = found
            if (devices.none { it.udid == selected }) selected = devices.firstOrNull { it.udid.isNotEmpty() }?.udid.orEmpty()
            val labels = devices.map { "${it.name.ifEmpty { it.udid.ifEmpty { "Unpaired iPhone" } }} • ${it.pairing} • ${it.connection}" }
            dropdown.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, labels))
            val index = devices.indexOfFirst { it.udid == selected }
            dropdown.setText(labels.getOrNull(index).orEmpty(), false)
        }
        status.text = next.message
        pin.text = next.pin.orEmpty(); pin.visibility = if (next.pin == null) View.GONE else View.VISIBLE
        progress.visibility = if (next.busy) View.VISIBLE else View.GONE
        cancel.visibility = if (next.busy) View.VISIBLE else View.GONE
        refresh.isEnabled = !next.busy && !next.mirroring
        pair.isEnabled = !next.busy && !next.mirroring
        prepare.isEnabled = !next.busy && !next.mirroring && selected.isNotEmpty()
        connect.isEnabled = !next.busy && !next.mirroring && selected.isNotEmpty()
        disconnect.visibility = if (next.mirroring) View.VISIBLE else View.GONE
        connect.visibility = if (next.mirroring) View.GONE else View.VISIBLE
        dropdown.isEnabled = !next.busy && !next.mirroring
        deviceButtons.forEach { it.isEnabled = next.mirroring }
    }
    override fun onStart() {
        super.onStart(); started = true
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicast = wifi.createMulticastLock("scrctl-mdns").apply { setReferenceCounted(false); acquire() }
        networkKey = ""
        connectivity.registerDefaultNetworkCallback(networks)
        controller.resume()
    }
    override fun onStop() {
        started = false; main.removeCallbacks(networkUpdate)
        connectivity.unregisterNetworkCallback(networks)
        mirror.clear(); controller.pause()
        multicast?.let { if (it.isHeld) it.release() }; multicast = null
        pin.text = ""; pin.visibility = View.GONE
        super.onStop()
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus && ::mirror.isInitialized) mirror.releaseTouch()
    }
    override fun onDestroy() { controller.close(); super.onDestroy() }
}
