package com.kmapper.app

import android.app.*
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.kmapper.app.model.KeyMapping
import com.kmapper.app.model.MappingProfile
import com.kmapper.app.model.MappingType
import com.kmapper.app.model.MouseLookMapping
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

class KeyCaptureService : Service() {

    companion object {
        const val CHANNEL_ID = "kmapper_capture"
        const val NOTIF_ID = 1
        const val EXTRA_PROFILE_NAME = "profile_name"
        @Volatile var isRunning = false
    }

    private lateinit var shell: RootShell
    private lateinit var injector: TouchInjector
    private var captureProcess: Process? = null
    private var keyMap: Map<String, KeyMapping> = emptyMap()
    private var mouseLook: MouseLookMapping? = null
    private var aimX = 0
    private var aimY = 0
    private var aimDown = false
    private val toggledOn = HashSet<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val profileName = intent?.getStringExtra(EXTRA_PROFILE_NAME)
        startForeground(NOTIF_ID, buildNotification())
        thread { startCapture(profileName) }
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "KMapper capture", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("KMapper active")
            .setContentText("Keyboard/mouse -> touch mapping running")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
    }

    private fun failWith(message: String) {
        android.os.Handler(mainLooper).post {
            android.widget.Toast.makeText(this, "KMapper: $message", android.widget.Toast.LENGTH_LONG).show()
            val mgr = getSystemService(NotificationManager::class.java)
            mgr.notify(
                NOTIF_ID,
                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("KMapper failed to start")
                    .setContentText(message)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                    .setSmallIcon(android.R.drawable.ic_menu_compass)
                    .setOngoing(false)
                    .build()
            )
        }
        stopSelf()
    }

    private fun screenSize(): Pair<Int, Int> {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        return dm.widthPixels to dm.heightPixels
    }

    private fun startCapture(profileName: String?) {
        shell = RootShell()
        if (!shell.open()) {
            failWith("Root access denied or unavailable. Grant root for KMapper in Magisk/KernelSU and try again.")
            return
        }

        val profile: MappingProfile? = profileName?.let { ProfileStorage(this).load(it) }
        keyMap = profile?.keyMappings?.associateBy { it.keyName } ?: emptyMap()
        mouseLook = profile?.mouseLook

        val scanner = InputDeviceScanner(shell)
        val devices = scanner.scan()
        val touchscreen = scanner.findTouchscreen(devices)
        if (touchscreen == null) {
            failWith("Couldn't identify the touchscreen device (no ABS_MT capability found in getevent -i). This ROM/kernel may report input differently than expected.")
            return
        }
        val (screenW, screenH) = screenSize()
        injector = TouchInjector(
            shell,
            touchscreen.node,
            touchscreen.absMtXMin, touchscreen.absMtXMax,
            touchscreen.absMtYMin, touchscreen.absMtYMax,
            screenW, screenH
        )

        mouseLook?.let {
            aimX = it.centerX
            aimY = it.centerY
        }

        val inputDevices = scanner.findExternalKeyboardsAndMice(this, devices, touchscreen.node)
        if (inputDevices.isEmpty()) {
            failWith("No external keyboard/mouse detected. Open 'Fix Devices' from the app and manually select your keyboard/mouse from the raw device list.")
            return
        }

        // Bring up the floating live editor/toolbar on top of the game now that
        // capture is confirmed running.
        startService(Intent(this, OverlayEditorService::class.java).apply {
            putExtra(OverlayEditorService.EXTRA_PROFILE_NAME, profileName)
        })

        // getevent can watch multiple nodes in one process; pass them all as args.
        val nodesArg = inputDevices.joinToString(" ") { it.node }
        val process = shell.openStream("getevent -l $nodesArg")
        captureProcess = process
        isRunning = true

        val reader = BufferedReader(InputStreamReader(process.inputStream))
        var line: String?
        while (isRunning) {
            line = reader.readLine() ?: break
            handleEventLine(line)
        }
        isRunning = false
    }

    // Example lines:
    // /dev/input/event3: EV_KEY       KEY_W               DOWN
    // /dev/input/event5: EV_REL       REL_X               00000003
    private val keyLineRegex = Regex("EV_KEY\\s+(\\S+)\\s+(DOWN|UP|REPEAT)")
    private val relLineRegex = Regex("EV_REL\\s+(REL_X|REL_Y)\\s+([0-9a-fA-F]+)")

    private fun handleEventLine(line: String) {
        keyLineRegex.find(line)?.let { m ->
            val keyName = m.groupValues[1]
            val action = m.groupValues[2]
            if (action != "REPEAT") onKeyEvent(keyName, action == "DOWN")
            return
        }
        relLineRegex.find(line)?.let { m ->
            val axis = m.groupValues[1]
            val raw = m.groupValues[2].toLong(16)
            // getevent prints REL deltas as 32-bit hex; convert two's-complement to signed.
            val signed = if (raw > 0x7FFFFFFFL) (raw - 0x100000000L).toInt() else raw.toInt()
            onMouseDelta(axis, signed)
        }
    }

    private fun onKeyEvent(keyName: String, isDown: Boolean) {
        val mapping = keyMap[keyName] ?: return
        val pointerId = keyName.hashCode()
        when (mapping.type) {
            MappingType.TAP -> if (isDown) injector.tap(pointerId, mapping.x, mapping.y)
            MappingType.HOLD -> if (isDown) injector.down(pointerId, mapping.x, mapping.y) else injector.up(pointerId)
            MappingType.TOGGLE -> if (isDown) {
                if (toggledOn.contains(keyName)) {
                    injector.up(pointerId)
                    toggledOn.remove(keyName)
                } else {
                    injector.down(pointerId, mapping.x, mapping.y)
                    toggledOn.add(keyName)
                }
            }
        }
    }

    private val AIM_POINTER_ID = -999

    private fun onMouseDelta(axis: String, delta: Int) {
        val look = mouseLook ?: return
        if (!look.enabled) return

        if (!aimDown) {
            injector.down(AIM_POINTER_ID, aimX, aimY)
            aimDown = true
        }

        val scaled = (delta * look.sensitivity).toInt()
        if (axis == "REL_X") aimX += scaled else aimY += scaled

        aimX = aimX.coerceIn(look.centerX - look.maxRadius, look.centerX + look.maxRadius)
        aimY = aimY.coerceIn(look.centerY - look.maxRadius, look.centerY + look.maxRadius)

        injector.move(AIM_POINTER_ID, aimX, aimY)

        // Recenter shortly after movement stops, like a mouse-look "flick" -- keeps the
        // virtual finger from drifting to the edge of its clamp radius over time.
        recenterHandle?.let { recenterThreadAlive = false }
        scheduleRecenter(look)
    }

    private var recenterHandle: Thread? = null
    private var recenterThreadAlive = false

    private fun scheduleRecenter(look: MouseLookMapping) {
        recenterThreadAlive = true
        recenterHandle = thread {
            Thread.sleep(120)
            if (recenterThreadAlive) {
                aimX = look.centerX
                aimY = look.centerY
                if (aimDown) injector.move(AIM_POINTER_ID, aimX, aimY)
            }
        }
    }

    override fun onDestroy() {
        isRunning = false
        captureProcess?.destroy()
        if (::injector.isInitialized) injector.releaseAll()
        if (::shell.isInitialized) shell.close()
        stopService(Intent(this, OverlayEditorService::class.java))
        super.onDestroy()
    }
}
