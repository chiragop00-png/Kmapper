package com.kmapper.app

import android.app.AlertDialog
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.*
import android.widget.*
import com.kmapper.app.model.KeyMapping
import com.kmapper.app.model.MappingType
import com.kmapper.app.model.MouseLookMapping
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * The floating control layer shown ON TOP of the running game. Everything
 * here uses small, individually-touchable WindowManager overlay windows
 * (one per bubble/dot) rather than a single full-screen window, so the vast
 * majority of the screen stays pass-through and doesn't block gameplay --
 * only the actual toolbar/dots you see intercept touches.
 */
class OverlayEditorService : Service() {

    companion object {
        const val EXTRA_PROFILE_NAME = "profile_name"
        private const val TAG_TOUCHSCREEN_EXCLUDE = "" // filled at runtime
    }

    private lateinit var wm: WindowManager
    private lateinit var storage: ProfileStorage
    private var profileName: String = ""

    private var toolbarView: View? = null
    private var expanded = false
    private var panelView: View? = null

    private val zoneViews = mutableMapOf<String, View>() // keyName -> its floating view
    private var aimView: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        profileName = intent?.getStringExtra(EXTRA_PROFILE_NAME) ?: return START_NOT_STICKY
        storage = ProfileStorage(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        if (toolbarView == null) {
            addToolbar()
            renderExistingZones()
        }
        return START_STICKY
    }

    private fun overlayType() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

    private fun baseParams(w: Int, h: Int, touchable: Boolean = true) = WindowManager.LayoutParams(
        w, h, overlayType(),
        (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    // ---------- Toolbar bubble ----------

    private fun addToolbar() {
        val dm = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)

        val bubble = TextView(this).apply {
            text = "KM"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#CC3388FF"))
            setPadding(28, 20, 28, 20)
            textSize = 14f
        }
        val params = baseParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
        params.x = 20
        params.y = dm.heightPixels / 3

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        bubble.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY
                    startX = params.x; startY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - downX).toInt(); val dy = (ev.rawY - downY).toInt()
                    if (abs(dx) > 8 || abs(dy) > 8) moved = true
                    params.x = startX + dx
                    params.y = startY + dy
                    wm.updateViewLayout(v, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) toggleToolbarPanel()
                    true
                }
                else -> false
            }
        }
        toolbarView = bubble
        wm.addView(bubble, params)
    }

    private fun toggleToolbarPanel() {
        if (expanded) {
            panelView?.let { wm.removeView(it) }
            panelView = null
            expanded = false
            return
        }
        expanded = true

        val statusText = TextView(this).apply {
            text = "KMapper -- ${profileName}\nKeyboard/mouse devices detected: scanning..."
            setTextColor(Color.WHITE)
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#EE1B1F3B"))
            setPadding(24, 24, 24, 24)
        }
        panel.addView(statusText)
        panel.addView(smallButton("+ Add key mapping") { addNewZoneFlow() })
        panel.addView(smallButton("Set mouse-look center") { setAimFlow() })
        panel.addView(smallButton("Fix device detection") {
            startActivity(Intent(this, DeviceScanActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        })
        panel.addView(smallButton("Stop mapping") { stopEverything() })

        val params = baseParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
        params.x = 20
        params.y = 200
        panelView = panel
        wm.addView(panel, params)

        refreshDeviceStatus(statusText)
    }

    private fun refreshDeviceStatus(statusText: TextView) {
        thread {
            val s = RootShell()
            val text = if (s.open()) {
                val scanner = InputDeviceScanner(s)
                val devices = scanner.scan()
                val ts = scanner.findTouchscreen(devices)
                val ext = scanner.findExternalKeyboardsAndMice(this, devices, ts?.node)
                s.close()
                "${ext.size} found" + if (ext.isNotEmpty()) " (${ext.joinToString { it.name }})" else ""
            } else {
                "root denied"
            }
            android.os.Handler(mainLooper).post {
                statusText.text = "KMapper -- ${profileName}\nKeyboard/mouse devices detected: $text"
            }
        }
    }

    private fun smallButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        }

    // ---------- Zone dots ----------

    private fun renderExistingZones() {
        val profile = storage.load(profileName) ?: return
        profile.keyMappings.forEach { addZoneView(it) }
        profile.mouseLook?.let { addAimView(it) }
    }

    private fun addZoneView(mapping: KeyMapping) {
        zoneViews[mapping.keyName]?.let { wm.removeView(it) }

        val dot = TextView(this).apply {
            text = mapping.keyName.removePrefix("KEY_")
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#993388FF"))
            setPadding(16, 10, 16, 10)
        }
        val params = baseParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
        params.x = mapping.x - 30
        params.y = mapping.y - 30

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        dot.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY
                    startX = params.x; startY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - downX).toInt(); val dy = (ev.rawY - downY).toInt()
                    if (abs(dx) > 8 || abs(dy) > 8) moved = true
                    params.x = startX + dx
                    params.y = startY + dy
                    wm.updateViewLayout(v, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        updateZonePosition(mapping.keyName, params.x + 30, params.y + 30)
                    } else {
                        showZoneMenu(mapping)
                    }
                    true
                }
                else -> false
            }
        }
        zoneViews[mapping.keyName] = dot
        wm.addView(dot, params)
    }

    private fun updateZonePosition(keyName: String, x: Int, y: Int) {
        val profile = storage.load(profileName) ?: return
        val updated = profile.keyMappings.map { if (it.keyName == keyName) it.copy(x = x, y = y) else it }
        storage.save(profile.copy(keyMappings = updated))
    }

    private fun showZoneMenu(mapping: KeyMapping) {
        val dialog = AlertDialog.Builder(this)
            .setTitle(mapping.keyName)
            .setMessage("${mapping.type} at (${mapping.x}, ${mapping.y})")
            .setPositiveButton("Remove") { _, _ ->
                val profile = storage.load(profileName) ?: return@setPositiveButton
                storage.save(profile.copy(keyMappings = profile.keyMappings.filterNot { it.keyName == mapping.keyName }))
                zoneViews.remove(mapping.keyName)?.let { wm.removeView(it) }
            }
            .setNegativeButton("Close", null)
            .create()
        dialog.window?.setType(overlayType())
        dialog.show()
    }

    private var pendingCaptureProcess: Process? = null

    private fun addNewZoneFlow() {
        toggleToolbarPanel() // close panel first
        val progress = AlertDialog.Builder(this)
            .setTitle("Press the key you want to bind...")
            .setCancelable(true)
            .setOnCancelListener { pendingCaptureProcess?.destroy() }
            .create()
        progress.window?.setType(overlayType())
        progress.show()

        thread {
            val captured = captureNextKeyPress()
            android.os.Handler(mainLooper).post {
                progress.dismiss()
                if (captured == null) {
                    Toast.makeText(this, "No key captured / no device detected. Try 'Fix device detection'.", Toast.LENGTH_LONG).show()
                    return@post
                }
                askZonePlacement(captured)
            }
        }
    }

    private fun askZonePlacement(keyName: String) {
        val typeOptions = arrayOf("TAP (quick tap)", "HOLD (held while key down)", "TOGGLE (press to toggle on/off)")
        val dialog = AlertDialog.Builder(this)
            .setTitle("Bound to $keyName -- choose type")
            .setItems(typeOptions) { _, which ->
                val type = when (which) {
                    0 -> MappingType.TAP
                    1 -> MappingType.HOLD
                    else -> MappingType.TOGGLE
                }
                val dm = DisplayMetrics()
                wm.defaultDisplay.getRealMetrics(dm)
                val mapping = KeyMapping(keyName, dm.widthPixels / 2, dm.heightPixels / 2, type)
                val profile = storage.load(profileName) ?: return@setItems
                val updated = profile.keyMappings.filterNot { it.keyName == keyName } + mapping
                storage.save(profile.copy(keyMappings = updated))
                addZoneView(mapping)
                Toast.makeText(this, "Added -- drag the new dot to where you want it on screen", Toast.LENGTH_LONG).show()
            }
            .create()
        dialog.window?.setType(overlayType())
        dialog.show()
    }

    private fun setAimFlow() {
        toggleToolbarPanel()
        val dm = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        val look = MouseLookMapping(true, dm.widthPixels * 3 / 4, dm.heightPixels / 2, 1.0f, 300)
        val profile = storage.load(profileName) ?: return
        storage.save(profile.copy(mouseLook = look))
        addAimView(look)
        Toast.makeText(this, "Drag the AIM dot to where mouse-look should center", Toast.LENGTH_LONG).show()
    }

    private fun addAimView(look: MouseLookMapping) {
        aimView?.let { wm.removeView(it) }
        val dot = TextView(this).apply {
            text = "AIM"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#99FF8833"))
            setPadding(16, 10, 16, 10)
        }
        val params = baseParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
        params.x = look.centerX - 30
        params.y = look.centerY - 30

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        dot.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY
                    startX = params.x; startY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - downX).toInt(); val dy = (ev.rawY - downY).toInt()
                    if (abs(dx) > 8 || abs(dy) > 8) moved = true
                    params.x = startX + dx
                    params.y = startY + dy
                    wm.updateViewLayout(v, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        val profile = storage.load(profileName) ?: return@setOnTouchListener true
                        val ml = profile.mouseLook?.copy(centerX = params.x + 30, centerY = params.y + 30)
                        storage.save(profile.copy(mouseLook = ml))
                    }
                    true
                }
                else -> false
            }
        }
        aimView = dot
        wm.addView(dot, params)
    }

    /** Opens its own short-lived root getevent read (independent of KeyCaptureService's
     *  persistent one -- Linux evdev nodes support multiple simultaneous readers) and
     *  returns the first KEY_* down event, or null if nothing came in / no root. */
    private fun captureNextKeyPress(): String? {
        val s = RootShell()
        if (!s.open()) return null
        val scanner = InputDeviceScanner(s)
        val devices = scanner.scan()
        val ts = scanner.findTouchscreen(devices)
        val candidates = scanner.findExternalKeyboardsAndMice(this, devices, ts?.node)
        if (candidates.isEmpty()) { s.close(); return null }

        val nodesArg = candidates.joinToString(" ") { it.node }
        val process = s.openStream("getevent -l $nodesArg")
        pendingCaptureProcess = process
        val reader = process.inputStream.bufferedReader()
        val regex = Regex("EV_KEY\\s+(\\S+)\\s+DOWN")
        var captured: String? = null
        var line: String?
        // Note: readLine() blocks, so this loop is bounded by the process being
        // destroyed (dialog cancel) or a key actually being pressed -- not by
        // wall-clock time. A stuck "waiting" dialog is closed via cancel, which
        // triggers destroy() above and unblocks the read.
        while (true) {
            line = reader.readLine() ?: break
            val m = regex.find(line) ?: continue
            captured = m.groupValues[1]
            break
        }
        process.destroy()
        pendingCaptureProcess = null
        s.close()
        return captured
    }

    private fun stopEverything() {
        stopService(Intent(this, KeyCaptureService::class.java))
        stopSelf()
    }

    override fun onDestroy() {
        toolbarView?.let { runCatching { wm.removeView(it) } }
        panelView?.let { runCatching { wm.removeView(it) } }
        zoneViews.values.forEach { runCatching { wm.removeView(it) } }
        aimView?.let { runCatching { wm.removeView(it) } }
        super.onDestroy()
    }
}
