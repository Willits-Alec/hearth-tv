package com.alec.hearthtv.voice

/** One spoken instruction, already resolved against the TV's real app and input names. */
sealed class VoiceCommand {
    data class Volume(val steps: Int) : VoiceCommand()
    data class Mute(val on: Boolean) : VoiceCommand()
    data class Power(val on: Boolean) : VoiceCommand()
    data class LaunchApp(val app: String) : VoiceCommand()
    data class SelectInput(val input: String) : VoiceCommand()
    data class Key(val key: String) : VoiceCommand()
    data object FixSound : VoiceCommand()
    data class NightMode(val on: Boolean) : VoiceCommand()
    data class SpeechEnhancement(val on: Boolean) : VoiceCommand()
    data class TypeText(val text: String) : VoiceCommand()

    /** Spoken search: the app opens a box on the TV if it has to, then types this (SCOPE.md §9.4). */
    data class SearchTv(val query: String) : VoiceCommand()
    data class Unknown(val heard: String) : VoiceCommand()

    /** Short confirmation for the toast after the command ran. */
    fun describe(): String = when (this) {
        is Volume -> if (steps > 0) "Volume up" + (if (steps > 1) " $steps" else "") else "Volume down" + (if (steps < -1) " ${-steps}" else "")
        is Mute -> if (on) "Mute" else "Unmute"
        is Power -> if (on) "TV on" else "TV off"
        is LaunchApp -> "Open $app"
        is SelectInput -> "Switch to $input"
        is Key -> when (key) {
            "Return" -> "Back"
            "Confirm" -> "OK"
            "Forward" -> "Fast forward"
            "ActionMenu" -> "Menu"
            "Display" -> "Info"
            else -> key
        }
        FixSound -> "Fix the sound"
        is NightMode -> "Night sound ${if (on) "on" else "off"}"
        is SpeechEnhancement -> "Speech enhancement ${if (on) "on" else "off"}"
        is TypeText -> "Type “$text”"
        is SearchTv -> "Search the TV for “$query”"
        is Unknown -> "Didn't catch that: “$heard”"
    }
}
