package com.kmapper.app

import android.content.Context

data class InputDevice(
    val node: String,      // e.g. /dev/input/event4
    val name: String,
    val hasKeys: Boolean,
    val hasRelMotion: Boolean,   // mouse dx/dy
    val hasAbsMt: Boolean,       // multitouch touchscreen
    val absMtXMax: Int = 0,
    val absMtYMax: Int = 0,
    val absMtXMin: Int = 0,
    val absMtYMin: Int = 0
)

/**
 * Parses `getevent -i` output to classify every /dev/input node.
 *
 * IMPORTANT: node names (event0, event1, ...) are NOT stable across reboots
 * on all devices/kernels. Always re-scan on service start rather than caching
 * a node path in the saved profile; we cache device NAME instead and re-resolve
 * the node at runtime.
 *
 * Detection is intentionally loose because `getevent -i` output formatting
 * (whether it prints hex type codes like "(0002)", spacing, etc.) varies
 * across Android versions/toybox builds. If a device still isn't picked up
 * automatically, use the in-app "Scan Devices" debug screen to see the raw
 * output and manually mark the correct device(s) -- that choice is stored
 * in SharedPreferences and overrides the heuristic.
 */
class InputDeviceScanner(private val shell: RootShell) {

    companion object {
        private const val PREFS = "kmapper_prefs"
        private const val KEY_MANUAL_DEVICES = "manual_input_device_names"

        fun getManualOverride(context: Context): Set<String> =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getStringSet(KEY_MANUAL_DEVICES, emptySet()) ?: emptySet()

        fun setManualOverride(context: Context, deviceNames: Set<String>) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putStringSet(KEY_MANUAL_DEVICES, deviceNames).apply()
        }
    }

    /** Raw, unparsed `getevent -i` text -- useful for the debug screen and for
     *  diagnosing detection problems together (paste this if something's wrong). */
    fun scanRaw(): String = shell.runQuick("getevent -i")

    fun scan(): List<InputDevice> {
        val raw = scanRaw()
        val devices = mutableListOf<InputDevice>()

        // getevent -i prints stanzas like:
        // add device 4: /dev/input/event4
        //   name:     "some-keyboard"
        //   events:
        //     KEY (0001): KEY_A KEY_B ...
        //     ABS (0003): ABS_MT_POSITION_X ...
        //
        // IMPORTANT: some ROMs/toybox builds print raw hex codes instead of
        // symbolic names (e.g. "0035" instead of "ABS_MT_POSITION_X" for the
        // exact same capability). Every check below matches EITHER form.
        // Relevant hex codes (from linux/input-event-codes.h):
        //   REL_X = 0000, REL_Y = 0001
        //   ABS_MT_POSITION_X = 0035, ABS_MT_POSITION_Y = 0036
        val stanzas = raw.split(Regex("(?=add device \\d+:)"))
        for (block in stanzas) {
            val nodeMatch = Regex("add device \\d+: (/dev/input/\\S+)").find(block) ?: continue
            val node = nodeMatch.groupValues[1]
            val nameMatch = Regex("name:\\s+\"([^\"]*)\"").find(block)
            val name = nameMatch?.groupValues?.get(1) ?: "unknown"

            val hasKeys = block.contains("KEY (0001)")

            // REL entries are listed on a single line after the header, e.g.
            // "REL (0002): 0000  0001  0008" -- scope to just that line so we
            // don't accidentally match unrelated "0000"s elsewhere in the
            // stanza (vendor/product/version fields are literally "0000" on
            // many devices).
            val relLine = Regex("REL \\(0002\\):([^\\n]*)").find(block)?.groupValues?.get(1) ?: ""
            val hasRelMotion = Regex("\\b(0000|REL_X)\\b").containsMatchIn(relLine)

            // ABS entries span multiple lines (one per code, with value/min/max).
            // Scope to the ABS section specifically, ending at the next section
            // header or "input props:", again to avoid false hits from
            // unrelated "0035"/"0036" appearing in, say, a KEY code list.
            val absSection = Regex(
                "ABS \\(0003\\):(.*?)(?=\\n\\s*(?:KEY|REL|MSC|SW|LED|SND|REP|FF)\\s*\\(|input props:|\\z)",
                RegexOption.DOT_MATCHES_ALL
            ).find(block)?.groupValues?.get(1) ?: ""

            val hasAbsMt = absSection.contains("ABS_MT_POSITION_X") ||
                Regex("\\b0035\\b").containsMatchIn(absSection)

            var xMin = 0; var xMax = 0; var yMin = 0; var yMax = 0
            if (hasAbsMt) {
                Regex("(?:ABS_MT_POSITION_X|0035)\\s*:\\s*value\\s*(-?\\d+),\\s*min\\s*(-?\\d+),\\s*max\\s*(-?\\d+)")
                    .find(absSection)?.let {
                        xMin = it.groupValues[2].toInt(); xMax = it.groupValues[3].toInt()
                    }
                Regex("(?:ABS_MT_POSITION_Y|0036)\\s*:\\s*value\\s*(-?\\d+),\\s*min\\s*(-?\\d+),\\s*max\\s*(-?\\d+)")
                    .find(absSection)?.let {
                        yMin = it.groupValues[2].toInt(); yMax = it.groupValues[3].toInt()
                    }
            }

            devices.add(
                InputDevice(node, name, hasKeys, hasRelMotion, hasAbsMt, xMax, yMax, xMin, yMin)
            )
        }
        return devices
    }

    fun findTouchscreen(devices: List<InputDevice>): InputDevice? =
        devices.filter { it.hasAbsMt }.maxByOrNull { it.absMtXMax * it.absMtYMax }

    /**
     * External keyboards/mice: anything with key or relative-motion capability
     * that ISN'T the touchscreen. If the user has manually picked devices via
     * the debug screen, that list wins outright (exact name match).
     */
    fun findExternalKeyboardsAndMice(
        context: Context,
        devices: List<InputDevice>,
        excludeNode: String?
    ): List<InputDevice> {
        val manual = getManualOverride(context)
        if (manual.isNotEmpty()) {
            return devices.filter { it.name in manual && it.node != excludeNode }
        }
        return devices.filter {
            it.node != excludeNode && (it.hasKeys || it.hasRelMotion) && !it.hasAbsMt
        }
    }
}
