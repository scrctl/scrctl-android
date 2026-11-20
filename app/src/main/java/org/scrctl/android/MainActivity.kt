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
import android.text.InputType
import android.view.Gravity
import android.view.MenuItem
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
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

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
    private lateinit var refresh: MenuItem
    private lateinit var prepare: MaterialButton
    private lateinit var connect: MaterialButton
    private lateinit var connectByAddress: MaterialButton
    private lateinit var cancel: MaterialButton
    private lateinit var hardwareDecode: MaterialSwitch
    private val preferences by lazy { getSharedPreferences("mirror", MODE_PRIVATE) }
    private var devices = emptyList<Device>()
    private var selected = ""
    private var state = UiState("Starting…", true)
    private val main = Handler(Looper.getMainLooper())
    private var started = false
    private var networkKey = ""
    private var multicast: WifiManager.MulticastLock? = null
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private fun activeNetworkKey(network: Network?): String {
        val properties = network?.let(connectivity::getLinkProperties)
        return "${network?.networkHandle}:${properties?.interfaceName}"
    }
    private val networkUpdate = Runnable {
        if (started) {
            val network = connectivity.activeNetwork
            val key = activeNetworkKey(network)
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
    private fun button(parent: LinearLayout, label: String, primary: Boolean = false, action: () -> Unit): MaterialButton {
        val style = if (primary) com.google.android.material.R.attr.materialButtonStyle
                    else com.google.android.material.R.attr.materialButtonOutlinedStyle
        return MaterialButton(this, null, style).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
            val inline = parent.orientation == LinearLayout.HORIZONTAL
            val params = if (inline) LinearLayout.LayoutParams(0, -2, 1f) else LinearLayout.LayoutParams(-1, -2)
            if (inline) params.setMargins(dp(4), 0, dp(4), 0)
            parent.addView(this, params)
        }
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
        listPage.addView(MaterialToolbar(this).apply {
            title = "scrctl"
            refresh = menu.add("Refresh devices").setOnMenuItemClickListener {
                controller.refresh()
                true
            }
        })
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(16)) }
        scroll.addView(content)
        listPage.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(TextView(this).apply {
            text = "iPhones"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            ViewCompat.setAccessibilityHeading(this, true)
        })
        status = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(6), 0, dp(10))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        content.addView(status)
        pin = TextView(this).apply { textSize = 36f; typeface = Typeface.MONOSPACE; textAlignment = View.TEXT_ALIGNMENT_CENTER }
        content.addView(pin)
        progress = LinearProgressIndicator(this).apply { isIndeterminate = true }
        content.addView(progress, LinearLayout.LayoutParams(-1, dp(4)))
        cancel = button(content, "Cancel") { controller.cancel() }
        deviceList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(deviceList)
        connect = button(content, "Connect", primary = true) { controller.mirror(selected, hardwareDecode.isChecked) }
        prepare = button(content, "Prepare DDI") { controller.prepare(selected) }
        val actions = row(content)
        pair = button(actions, "Pair iPhone") { controller.pair() }
        connectByAddress = button(actions, "Connect by address") { showAddressDialog() }
        hardwareDecode = MaterialSwitch(this).apply {
            text = "Prefer hardware decoding"
            isChecked = preferences.getBoolean("hardware_decode", true)
            setOnCheckedChangeListener { _, checked ->
                preferences.edit().putBoolean("hardware_decode", checked).apply()
            }
        }
        content.addView(hardwareDecode)
        mirrorPage = FrameLayout(this).apply { visibility = View.GONE }
        mirror = MirrorView(this)
        mirrorPage.addView(mirror, FrameLayout.LayoutParams(-1, -1))
        val back = MaterialButton(this).apply { text = "‹"; contentDescription = "Back to devices"; setOnClickListener { disconnect() } }
        mirrorPage.addView(back, FrameLayout.LayoutParams(dp(56), dp(48), Gravity.TOP or Gravity.START).apply { setMargins(dp(12), dp(12), dp(12), 0) })
        val menu = MaterialButton(this).apply { text = "⋮"; contentDescription = "iPhone controls" }
        menu.setOnClickListener {
            PopupMenu(this, menu).apply {
                this.menu.add(0, 5, 0, "Decoder: ${state.decoder ?: "waiting for video"}").isEnabled = false
                listOf("Home", "Volume down", "Volume up", "Lock", "Disconnect").forEachIndexed { index, label -> this.menu.add(0, index, index, label) }
                setOnMenuItemClickListener { item ->
                    when (item.itemId) { 0 -> controller.button(0x40); 1 -> controller.button(0xEA); 2 -> controller.button(0xE9); 3 -> controller.button(0x30); 4 -> disconnect() }
                    true
                }
            }.show()
        }
        mirrorPage.addView(menu, FrameLayout.LayoutParams(dp(56), dp(48), Gravity.TOP or Gravity.END).apply { setMargins(dp(12), dp(12), dp(12), 0) })
        ViewCompat.setOnApplyWindowInsetsListener(mirrorPage) { _, insets ->
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            for (control in listOf(back, menu)) {
                val params = control.layoutParams as FrameLayout.LayoutParams
                params.leftMargin = dp(12) + cutout.left
                params.topMargin = dp(12) + cutout.top
                params.rightMargin = dp(12) + cutout.right
                control.layoutParams = params
            }
            insets
        }
        root.addView(mirrorPage, FrameLayout.LayoutParams(-1, -1))
        controller = SessionController(applicationContext, filesDir, cacheDir, { render(it) }, { mirror.submit(it) })
        mirror.acceptsFrame = controller::acceptsFrame; mirror.onTouch = controller::touch
        onBackPressedDispatcher.addCallback(this) { if (state.mirroring) disconnect() else finish() }
        setContentView(root); render(state)
    }
    private fun showAddressDialog() {
        val address = TextInputEditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setText(preferences.getString("wifi_address", ""))
        }
        val field = TextInputLayout(this).apply {
            hint = "IP address or hostname"
            addView(address)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(field, LinearLayout.LayoutParams(-1, -2))
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Connect by address")
            .setMessage("Use this when an already paired iPhone does not appear in the list. You can include a port.")
            .setView(content)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Connect", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val value = address.text.toString().trim()
                if (value.isEmpty()) {
                    field.error = "Enter the iPhone address"
                    return@setOnClickListener
                }
                preferences.edit().putString("wifi_address", value).apply()
                val udid = devices.firstOrNull { it.udid == selected && it.isPaired }?.udid.orEmpty()
                controller.mirror(udid, hardwareDecode.isChecked, value)
                dialog.dismiss()
            }
        }
        dialog.show()
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
            val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            details.addView(TextView(this).apply {
                textSize = 17f
                setTypeface(typeface, Typeface.BOLD)
                text = device.name.ifEmpty { "iPhone" }
            })
            details.addView(TextView(this).apply {
                textSize = 14f
                text = when {
                    device.connection == "connected" -> "Connected"
                    device.isPaired -> "Paired"
                    else -> "Pair this iPhone before connecting"
                }
            })
            if (device.udid.isNotEmpty()) {
                details.addView(TextView(this).apply {
                    textSize = 12f
                    typeface = Typeface.MONOSPACE
                    text = device.udid
                })
            }
            card.addView(details)
            card.setOnClickListener { selected = device.udid; render(state) }
            card.isEnabled = !state.busy && device.udid.isNotEmpty()
            deviceList.addView(card, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(8), 0, dp(8)) })
        }
    }
    private fun render(next: UiState) {
        if (isDestroyed) return
        val changed = state.mirroring != next.mirroring
        state = next
        next.devices?.let { found ->
            devices = found
            if (found.none { it.udid == selected }) selected = ""
            if (selected.isEmpty()) {
                selected = found.filter { it.udid.isNotEmpty() && it.isPaired }.singleOrNull()?.udid.orEmpty()
            }
        }
        status.text = next.message
        pin.text = next.pin.orEmpty(); pin.visibility = if (next.pin == null) View.GONE else View.VISIBLE
        progress.visibility = if (next.busy) View.VISIBLE else View.GONE
        cancel.visibility = if (next.cancellable) View.VISIBLE else View.GONE
        refresh.isEnabled = !next.busy; pair.isEnabled = !next.busy
        hardwareDecode.isEnabled = !next.busy
        connectByAddress.isEnabled = !next.busy
        val canConnect = devices.any { it.udid == selected && it.udid.isNotEmpty() && it.isPaired }
        val actionsVisibility = if (canConnect) View.VISIBLE else View.GONE
        prepare.visibility = actionsVisibility
        connect.visibility = actionsVisibility
        prepare.isEnabled = !next.busy
        connect.isEnabled = !next.busy
        renderDevices()
        if (changed || !next.mirroring) showMirror(next.mirroring)
    }
    override fun onStart() {
        super.onStart(); started = true
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicast = wifi.createMulticastLock("scrctl-mdns").apply { setReferenceCounted(false); acquire() }
        networkKey = activeNetworkKey(connectivity.activeNetwork)
        connectivity.registerDefaultNetworkCallback(networks)
        controller.resume()
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
