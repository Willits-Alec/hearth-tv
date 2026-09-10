package com.alec.hearthtv.fakes

import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards for a public repo: every fixture is present and none of them carries the owner's identifiers. */
class FixturesTest {
    private val required = listOf(
        "bravia/getPowerStatus.json", "bravia/getInterfaceInformation.json", "bravia/getSystemSupportedFunction.json",
        "bravia/getRemoteControllerInfo.json", "bravia/getCurrentExternalInputsStatus.json", "bravia/getVolumeInformation.json",
        "bravia/getSoundSettings.json", "bravia/getSupportedApiInfo.json", "bravia/error403.json",
        "bravia/accessControl_getMethodTypes.json", "bravia/getSystemInformation.json", "bravia/getWolMode.json",
        "bravia/getApplicationList.json", "bravia/getNetworkSettings.json", "bravia/getPlayingContentInfo.json",
        "bravia/ircc_Display.xml", "bravia/eureka_info.json",
        "sonos/sonos_device_description.xml", "sonos/sonos_GetVolume.xml", "sonos/sonos_GetMute.xml",
        "sonos/sonos_GetEQ_NightMode.xml", "sonos/sonos_GetEQ_DialogLevel.xml", "sonos/sonos_GetTransportInfo.xml",
        "sonos/sonos_GetMediaInfo.xml", "sonos/sonos_GetZoneGroupState.xml",
        "roku/roku_device_info.xml", "roku/roku_active_app.xml", "roku/roku_root.xml", "roku/roku_error_limited_mode.txt", "roku/roku_apps.xml",
        "bravia/getCurrentExternalInputsStatus_onRoku.json", "bravia/getPlayingContentInfo_roku.json", "bravia/getVolumeInformation_standby.json",
    )

    private val forbidden = listOf(
        "10.10.10.", "AC:80:0A", "ac:80:0a", "Family Room", "4053323", "18CABD2C", "456EAD47",
        "38420BE117BD", "38:42:0b:e1:17:bd", "F0F6C1E8CD24", "F0F6C1CFE2A0", "F0F6C1CFE07A", "949F3EB75C54",
        "d8:31:34", "Monarch2", "YJ0055155550", "CK4875155550",
    )

    @Test fun `every fixture the fakes rely on exists and is non-empty`() {
        required.forEach { assertTrue("empty fixture $it", Fixtures.text(it).isNotBlank()) }
    }

    @Test fun `no fixture carries the owner's addresses, serials or names`() {
        for (dir in listOf("bravia", "sonos", "roku")) {
            for (name in Fixtures.list(dir)) {
                if (name.endsWith(".md")) continue
                val text = Fixtures.text("$dir/$name")
                forbidden.forEach { needle -> assertTrue("$dir/$name leaks '$needle'", !text.contains(needle)) }
            }
        }
    }

    @Test fun `the IRCC table has the keys the remote screen needs`() {
        val table = Fixtures.text("bravia/getRemoteControllerInfo.json")
        listOf("Home", "Return", "Up", "Down", "Left", "Right", "Confirm", "VolumeUp", "VolumeDown", "Mute",
            "Play", "Pause", "Stop", "Rewind", "Forward", "Netflix", "YouTube", "Display", "Input", "TvPower", "WakeUp", "PowerOff")
            .forEach { key -> assertTrue("IRCC table lacks $key", table.contains("\"name\": \"$key\"")) }
    }
}
