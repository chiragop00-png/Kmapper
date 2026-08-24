package com.kmapper.app

import kotlin.math.roundToInt

// Linux input-event-codes.h constants we need.
private const val EV_SYN = 0
private const val EV_KEY = 1
private const val EV_ABS = 3

private const val SYN_REPORT = 0
private const val ABS_MT_SLOT = 0x2f
private const val ABS_MT_TRACKING_ID = 0x39
private const val ABS_MT_POSITION_X = 0x35
private const val ABS_MT_POSITION_Y = 0x36
private const val BTN_TOUCH = 0x14a

/**
 * Injects touches using Multitouch Protocol B directly on the touchscreen's
 * /dev/input node via `sendevent`. Each mapped "virtual finger" (a held key,
 * or the mouse-look pointer) gets its own MT slot number, starting high
 * (SLOT_BASE) so we never collide with the phone's own real touch slots
 * (usually 0-9, rarely more than a handful).
 *
 * Coordinates passed in are SCREEN pixels; they get rescaled into the
 * touchscreen's raw ABS_MT_POSITION min/max range, which is frequently NOT
 * the same as the screen resolution (e.g. panel reports 0-4095 while the
 * screen is 1080x2400).
 */
class TouchInjector(
    private val shell: RootShell,
    private val touchscreenNode: String,
    private val rawXMin: Int,
    private val rawXMax: Int,
    private val rawYMin: Int,
    private val rawYMax: Int,
    private val screenWidth: Int,
    private val screenHeight: Int
) {
    companion object {
        private const val SLOT_BASE = 20 // virtual slots start here, real fingers won't reach this high
        private const val MAX_VIRTUAL_SLOTS = 10
    }

    private val activeSlots = HashMap<Int, Int>() // virtualPointerId -> slotIndex
    private var nextTrackingId = 1000

    private fun toRawX(screenX: Int): Int {
        val ratio = screenX.toFloat() / screenWidth.toFloat()
        return (rawXMin + ratio * (rawXMax - rawXMin)).roundToInt().coerceIn(rawXMin, rawXMax)
    }

    private fun toRawY(screenY: Int): Int {
        val ratio = screenY.toFloat() / screenHeight.toFloat()
        return (rawYMin + ratio * (rawYMax - rawYMin)).roundToInt().coerceIn(rawYMin, rawYMax)
    }

    private fun send(type: Int, code: Int, value: Int) {
        shell.exec("sendevent $touchscreenNode $type $code $value")
    }

    private fun syn() = send(EV_SYN, SYN_REPORT, 0)

    private fun allocateSlot(pointerId: Int): Int? {
        if (activeSlots.containsKey(pointerId)) return activeSlots[pointerId]
        if (activeSlots.size >= MAX_VIRTUAL_SLOTS) return null
        val slot = SLOT_BASE + activeSlots.size
        activeSlots[pointerId] = slot
        return slot
    }

    /** Press down a new virtual touch point at (x, y) on screen, tagged with pointerId (e.g. hashed key code). */
    fun down(pointerId: Int, x: Int, y: Int) {
        val slot = allocateSlot(pointerId) ?: return
        val trackingId = nextTrackingId++
        send(EV_ABS, ABS_MT_SLOT, slot)
        send(EV_ABS, ABS_MT_TRACKING_ID, trackingId)
        send(EV_KEY, BTN_TOUCH, 1)
        send(EV_ABS, ABS_MT_POSITION_X, toRawX(x))
        send(EV_ABS, ABS_MT_POSITION_Y, toRawY(y))
        syn()
    }

    /** Move an already-down virtual touch point (used for mouse-look drag / joystick drag). */
    fun move(pointerId: Int, x: Int, y: Int) {
        val slot = activeSlots[pointerId] ?: return
        send(EV_ABS, ABS_MT_SLOT, slot)
        send(EV_ABS, ABS_MT_POSITION_X, toRawX(x))
        send(EV_ABS, ABS_MT_POSITION_Y, toRawY(y))
        syn()
    }

    /** Release a virtual touch point. */
    fun up(pointerId: Int) {
        val slot = activeSlots[pointerId] ?: return
        send(EV_ABS, ABS_MT_SLOT, slot)
        send(EV_ABS, ABS_MT_TRACKING_ID, -1)
        if (activeSlots.size <= 1) {
            send(EV_KEY, BTN_TOUCH, 0)
        }
        syn()
        activeSlots.remove(pointerId)
    }

    /** Quick tap for keys mapped as "single press = single tap" rather than "hold = hold". */
    fun tap(pointerId: Int, x: Int, y: Int, holdMs: Long = 40) {
        down(pointerId, x, y)
        Thread {
            Thread.sleep(holdMs)
            up(pointerId)
        }.start()
    }

    fun releaseAll() {
        activeSlots.keys.toList().forEach { up(it) }
    }
}
