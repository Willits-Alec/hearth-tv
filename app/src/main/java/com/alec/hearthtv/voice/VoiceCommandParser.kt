package com.alec.hearthtv.voice

/**
 * Turns a recognised utterance into one [VoiceCommand]. Pure Kotlin, no speech engine: the phone's recogniser
 * produces the text, this decides what it means. It knows the TV's live app and input names so loose phrases
 * ("open prime", "switch to roku") land on the right thing. Pinned by VoiceCommandParserTest.
 */
/** [inputs] must be in priority order: CEC device names before HDMI port labels. */
class VoiceCommandParser(apps: List<String>, inputs: List<String>) {
    private val apps = apps.distinct()
    private val inputs = inputs.distinct()

    companion object {
        private val NUMBER_WORDS = mapOf(
            "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
            "eight" to 8, "nine" to 9, "ten" to 10,
        )
        private val KEYS = listOf(
            listOf("pause") to "Pause",
            listOf("resume", "play") to "Play",
            listOf("stop") to "Stop",
            listOf("rewind") to "Rewind",
            listOf("fast forward", "skip forward", "forward") to "Forward",
            listOf("go home", "home") to "Home",
            listOf("go back", "back") to "Return",
            listOf("okay", "ok", "select", "enter", "confirm") to "Confirm",
            listOf("scroll up", "move up", "up") to "Up",
            listOf("scroll down", "move down", "down") to "Down",
            listOf("move left", "left") to "Left",
            listOf("move right", "right") to "Right",
            listOf("menu") to "ActionMenu",
            listOf("info") to "Display",
        )
    }

    fun parse(raw: String): VoiceCommand {
        val text = normalize(raw)
        if (text.isBlank()) return VoiceCommand.Unknown(raw)

        // sound & sonos first: they contain words ("on", "off", "down") that other rules would grab
        if (text.containsAny("fix the sound", "fix sound", "no sound", "sound is broken", "sound isn't working")) return VoiceCommand.FixSound
        if (text.containsAny("night mode", "night sound")) return VoiceCommand.NightMode(!text.endsWithOff())
        if (text.containsAny("speech enhancement", "dialog boost", "dialogue boost", "speech boost")) return VoiceCommand.SpeechEnhancement(!text.endsWithOff())

        if (text.startsWith("unmute")) return VoiceCommand.Mute(false)
        if (text.containsAny("mute")) return VoiceCommand.Mute(true)

        if (text.containsAny("volume", "louder", "quieter", "turn it up", "turn it down", "turn up", "turn down", "softer")) {
            val down = text.containsAny("down", "quieter", "lower", "softer")
            val steps = firstNumber(text) ?: 1
            return VoiceCommand.Volume(if (down) -steps else steps)
        }

        if (text.containsAny("power off", "turn off", "tv off", "shut it down", "shut down", "switch off", "shut it off")) return VoiceCommand.Power(false)
        if (text.containsAny("power on", "turn on", "tv on", "wake up", "switch on")) return VoiceCommand.Power(true)

        typed(text)?.let { return it }

        inputTarget(text)?.let { target ->
            matchInput(target)?.let { return VoiceCommand.SelectInput(it) }
            matchApp(target)?.let { return VoiceCommand.LaunchApp(it) }
        }

        appTarget(text)?.let { target ->
            matchApp(target)?.let { return VoiceCommand.LaunchApp(it) }
            matchInput(target)?.let { return VoiceCommand.SelectInput(it) }
        }

        for ((phrases, key) in KEYS) {
            if (phrases.any { text == it || text.startsWith("$it ") || text.endsWith(" $it") }) return VoiceCommand.Key(key)
        }

        // bare app or input name
        matchApp(text)?.let { return VoiceCommand.LaunchApp(it) }
        matchInput(text)?.let { return VoiceCommand.SelectInput(it) }

        return VoiceCommand.Unknown(raw)
    }

    // ── pieces ──────────────────────────────────────────────────────────────────────────────────────

    private fun normalize(raw: String): String {
        var t = raw.lowercase().replace(Regex("[^a-z0-9+ ]"), " ").replace(Regex("\\s+"), " ").trim()
        t = t.replace(Regex("\\bplease\\b"), "").replace(Regex("\\s+"), " ").trim()
        return t
    }

    private fun String.containsAny(vararg phrases: String) = phrases.any { p -> this == p || this.contains(p) }

    private fun String.endsWithOff() = Regex("\\boff\\b").containsMatchIn(this)

    private fun firstNumber(text: String): Int? {
        Regex("\\b(\\d{1,2})\\b").find(text)?.let { return it.groupValues[1].toInt() }
        return text.split(' ').firstNotNullOfOrNull { NUMBER_WORDS[it] }
    }

    private fun typed(text: String): VoiceCommand? {
        for (prefix in listOf("type ", "search for ", "search ", "look up ")) {
            if (text.startsWith(prefix) && text.length > prefix.length) return VoiceCommand.TypeText(text.removePrefix(prefix).trim())
        }
        return null
    }

    private fun inputTarget(text: String): String? {
        for (prefix in listOf("switch to ", "switch input to ", "change to ", "change input to ", "input ", "go to ")) {
            if (text.startsWith(prefix)) return stripArticle(text.removePrefix(prefix))
        }
        if (text.startsWith("hdmi")) return text
        return null
    }

    private fun appTarget(text: String): String? {
        for (prefix in listOf("open ", "launch ", "start ", "watch ", "play ", "put on ")) {
            if (text.startsWith(prefix) && text.length > prefix.length) return stripArticle(text.removePrefix(prefix))
        }
        return null
    }

    private fun stripArticle(s: String) = s.removePrefix("the ").removePrefix("my ").trim()

    private fun wordsToDigits(s: String): String =
        s.split(' ').map { w -> NUMBER_WORDS[w]?.toString() ?: w }.joinToString(" ")

    private fun canon(name: String): String =
        wordsToDigits(name.lowercase().replace("+", " plus").replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim())

    private fun matches(name: String, t: String): Boolean {
        val n = canon(name)
        return n == t || n.contains(t) || (t.contains(n) && n.length >= 3)
    }

    /** Apps: the exact name first, then the longest name containing the phrase ("prime" -> "Prime Video"; "youtube" stays "YouTube"). */
    private fun matchApp(target: String): String? {
        val t = canon(target)
        if (t.isBlank()) return null
        apps.firstOrNull { canon(it) == t }?.let { return it }
        return apps.filter { matches(it, t) }.maxByOrNull { canon(it).length }
    }

    /**
     * Inputs arrive in priority order (CEC devices first, the way the TV lists them), so the first name that matches
     * wins: "roku" -> the CEC device "Roku Ultra", not the stale "Roku" label somebody typed on an HDMI port.
     */
    private fun matchInput(target: String): String? {
        val t = canon(target)
        if (t.isBlank()) return null
        return inputs.firstOrNull { matches(it, t) }
    }
}
