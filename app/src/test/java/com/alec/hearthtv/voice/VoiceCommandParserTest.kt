package com.alec.hearthtv.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Voice control, written before the parser existed. The phone's speech recogniser turns speech into text; this
 * grammar turns text into one remote action. It knows the TV's real app and input names so "open prime" lands on
 * "Prime Video" and "switch to roku" lands on the CEC entry "Roku Ultra".
 */
class VoiceCommandParserTest {
    private val apps = listOf("Prime Video", "YouTube", "Netflix", "Apple TV", "Hulu", "Paramount+", "Peacock TV", "Spotify", "Plex", "YouTube Music")
    private val inputs = listOf("Roku Ultra", "Sonos Arc", "HDMI 1", "Game", "HDMI 3 (eARC/ARC)", "Roku", "Video")
    private val parser = VoiceCommandParser(apps, inputs)

    private fun p(text: String) = parser.parse(text)

    @Test fun `volume phrases`() {
        assertEquals(VoiceCommand.Volume(+1), p("volume up"))
        assertEquals(VoiceCommand.Volume(+1), p("Turn it up"))
        assertEquals(VoiceCommand.Volume(+1), p("louder"))
        assertEquals(VoiceCommand.Volume(-1), p("volume down"))
        assertEquals(VoiceCommand.Volume(-1), p("quieter please"))
        assertEquals(VoiceCommand.Volume(+3), p("volume up 3"))
        assertEquals(VoiceCommand.Volume(-5), p("turn the volume down by five"))
        assertEquals(VoiceCommand.Mute(true), p("mute"))
        assertEquals(VoiceCommand.Mute(false), p("unmute the tv"))
    }

    @Test fun `power phrases`() {
        assertEquals(VoiceCommand.Power(true), p("turn on the tv"))
        assertEquals(VoiceCommand.Power(true), p("tv on"))
        assertEquals(VoiceCommand.Power(false), p("turn the tv off"))
        assertEquals(VoiceCommand.Power(false), p("power off"))
        assertEquals(VoiceCommand.Power(false), p("shut it down"))
    }

    @Test fun `apps by loose name`() {
        assertEquals(VoiceCommand.LaunchApp("Prime Video"), p("open prime"))
        assertEquals(VoiceCommand.LaunchApp("Prime Video"), p("launch prime video"))
        assertEquals(VoiceCommand.LaunchApp("Netflix"), p("watch netflix"))
        assertEquals(VoiceCommand.LaunchApp("YouTube"), p("open youtube"))
        assertEquals(VoiceCommand.LaunchApp("YouTube Music"), p("open youtube music"))
        assertEquals(VoiceCommand.LaunchApp("Paramount+"), p("start paramount plus"))
        assertEquals(VoiceCommand.LaunchApp("Apple TV"), p("go to apple tv"))
    }

    @Test fun `inputs by device name, preferring the CEC device over a stale port label`() {
        assertEquals(VoiceCommand.SelectInput("Roku Ultra"), p("switch to roku"))
        assertEquals(VoiceCommand.SelectInput("Roku Ultra"), p("switch to the roku"))
        assertEquals(VoiceCommand.SelectInput("Game"), p("switch to the game"))
        assertEquals(VoiceCommand.SelectInput("HDMI 1"), p("input hdmi 1"))
        assertEquals(VoiceCommand.SelectInput("HDMI 1"), p("hdmi one"))
    }

    @Test fun `keys and media`() {
        assertEquals(VoiceCommand.Key("Pause"), p("pause"))
        assertEquals(VoiceCommand.Key("Play"), p("play"))
        assertEquals(VoiceCommand.Key("Play"), p("resume"))
        assertEquals(VoiceCommand.Key("Stop"), p("stop"))
        assertEquals(VoiceCommand.Key("Rewind"), p("rewind"))
        assertEquals(VoiceCommand.Key("Forward"), p("fast forward"))
        assertEquals(VoiceCommand.Key("Home"), p("go home"))
        assertEquals(VoiceCommand.Key("Return"), p("go back"))
        assertEquals(VoiceCommand.Key("Confirm"), p("ok"))
        assertEquals(VoiceCommand.Key("Confirm"), p("select"))
        assertEquals(VoiceCommand.Key("Up"), p("up"))
        assertEquals(VoiceCommand.Key("Down"), p("scroll down"))
        assertEquals(VoiceCommand.Key("Left"), p("left"))
        assertEquals(VoiceCommand.Key("Right"), p("move right"))
    }

    @Test fun `sound and sonos phrases`() {
        assertEquals(VoiceCommand.FixSound, p("fix the sound"))
        assertEquals(VoiceCommand.FixSound, p("no sound"))
        assertEquals(VoiceCommand.NightMode(true), p("night mode on"))
        assertEquals(VoiceCommand.NightMode(false), p("turn night sound off"))
        assertEquals(VoiceCommand.SpeechEnhancement(true), p("speech enhancement on"))
        assertEquals(VoiceCommand.SpeechEnhancement(false), p("dialog boost off"))
    }

    @Test fun `typing and searching`() {
        assertEquals(VoiceCommand.TypeText("dune part two"), p("type dune part two"))
        assertEquals(VoiceCommand.TypeText("hunter2"), p("enter hunter2"))
        // searching may have to open the box first, so it is its own command
        assertEquals(VoiceCommand.SearchTv("the bear"), p("search for the bear"))
        assertEquals(VoiceCommand.SearchTv("jaws"), p("search jaws"))
        assertEquals(VoiceCommand.SearchTv("the wire"), p("look up the wire"))
        assertEquals(VoiceCommand.SearchTv("top gun"), p("find top gun"))
    }

    @Test fun `anything else is Unknown and keeps the words`() {
        val u = p("make me a sandwich")
        assertTrue(u is VoiceCommand.Unknown)
        assertEquals("make me a sandwich", (u as VoiceCommand.Unknown).heard)
        assertTrue(p("") is VoiceCommand.Unknown)
    }

    @Test fun `commands describe themselves for the confirmation toast`() {
        assertEquals("Volume up", VoiceCommand.Volume(+1).describe())
        assertEquals("Volume down 3", VoiceCommand.Volume(-3).describe())
        assertEquals("Open Netflix", VoiceCommand.LaunchApp("Netflix").describe())
        assertEquals("Switch to Roku Ultra", VoiceCommand.SelectInput("Roku Ultra").describe())
        assertEquals("Type “jaws”", VoiceCommand.TypeText("jaws").describe())
        assertEquals("Search the TV for “jaws”", VoiceCommand.SearchTv("jaws").describe())
    }
}
