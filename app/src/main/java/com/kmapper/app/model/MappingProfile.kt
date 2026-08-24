package com.kmapper.app.model

enum class MappingType {
    TAP,        // key press -> quick tap (e.g. reload, jump)
    HOLD,       // key held down -> touch held down, released on key up (e.g. WASD movement)
    TOGGLE      // press once -> touch down, press again -> touch up
}

data class KeyMapping(
    val keyName: String,      // Linux KEY_* name, e.g. "KEY_W", captured from getevent
    val x: Int,                // screen pixel coordinates this key taps/holds
    val y: Int,
    val type: MappingType,
    val label: String = ""     // shown on the overlay editor, e.g. "Move Forward"
)

/**
 * Mouse movement is mapped to a virtual drag inside a joystick-style zone
 * (e.g. right half of screen for aiming), rather than 1:1 absolute position,
 * since raw mouse dx/dy has no absolute position of its own.
 */
data class MouseLookMapping(
    val enabled: Boolean = true,
    val centerX: Int,          // resting point of the virtual "aim finger"
    val centerY: Int,
    val sensitivity: Float = 1.0f,  // pixels of drag per raw mouse delta unit
    val maxRadius: Int = 300        // clamp how far the virtual finger drags from center before recentering
)

data class MappingProfile(
    val profileName: String,              // unique key, we use the game's package name
    val gameName: String = profileName,   // human-readable label shown in the list/UI
    val packageName: String = profileName,
    val keyMappings: List<KeyMapping> = emptyList(),
    val mouseLook: MouseLookMapping? = null
)
