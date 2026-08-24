package com.kmapper.app

import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

class DeviceScanActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val status = TextView(this).apply { text = "Requesting root, scanning /dev/input..." }
        root.addView(status)
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val saveBtn = Button(this).apply { text = "Save selection" }
        root.addView(saveBtn)
        setContentView(root)

        thread {
            val shell = RootShell()
            if (!shell.open()) {
                runOnUiThread {
                    status.text = "Root access was denied. Grant root for this app in Magisk/KernelSU and try again."
                }
                return@thread
            }
            val scanner = InputDeviceScanner(shell)
            val devices = scanner.scan()
            val currentManual = InputDeviceScanner.getManualOverride(this)
            val checkboxes = mutableListOf<Pair<CheckBox, String>>()

            runOnUiThread {
                status.text = if (devices.isEmpty())
                    "No input devices found at all -- root's getevent access may be blocked on this ROM."
                else
                    "Found ${devices.size} device(s). Tick the ones that are your real keyboard/mouse " +
                    "(their name usually hints at it), then Save. This overrides auto-detection."

                devices.forEach { d ->
                    val caps = buildString {
                        if (d.hasKeys) append("KEY ")
                        if (d.hasRelMotion) append("REL ")
                        if (d.hasAbsMt) append("ABS_MT(touchscreen) ")
                    }
                    val cb = CheckBox(this).apply {
                        text = "${d.name}\n  ${d.node}  [$caps]"
                        isChecked = d.name in currentManual
                    }
                    checkboxes.add(cb to d.name)
                    list.addView(cb)
                }

                val rawView = TextView(this).apply {
                    text = "\n--- raw getevent -i (for reference) ---\n" + scanner.scanRaw()
                    textSize = 10f
                    setPadding(0, 32, 0, 0)
                }
                list.addView(rawView)
            }

            saveBtn.setOnClickListener {
                val chosen = checkboxes.filter { it.first.isChecked }.map { it.second }.toSet()
                InputDeviceScanner.setManualOverride(this, chosen)
                Toast.makeText(this, "Saved. Restart mapping for a game to use it.", Toast.LENGTH_LONG).show()
                finish()
            }
            shell.close()
        }
    }
}
