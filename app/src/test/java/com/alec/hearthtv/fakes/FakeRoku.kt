package com.alec.hearthtv.fakes

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/**
 * A stand-in for the Roku Ultra (4660X, Roku OS 15.3.4) on HDMI 4, speaking ECP on :8060.
 *
 * Observed 2026-09-10: in **Limited** network-access mode only `query/device-info` and `query/active-app`
 * answered; everything else returned "ECP command not allowed in Limited mode." The owner then switched the
 * box to Default and the full app list was captured (`roku/roku_apps.xml`). The fake serves that list and can
 * be flipped back to Limited to test the "here is how to unlock it" path.
 */
class FakeRoku : AutoCloseable {
    val server = MockWebServer()
    val baseUrl: String get() = server.url("/").toString().trimEnd('/')

    /** The owner switched the box from Limited to Default on 2026-09-10; tests flip this to exercise the locked path. */
    var limitedMode: Boolean = false
    var activeAppId: String = "773622"
    var activeAppName: String = "Backdrops"
    /** id -> name, parsed from the captured `query/apps` reply. */
    val apps: LinkedHashMap<String, String> = LinkedHashMap<String, String>().apply {
        val xml = runCatching { Fixtures.text("roku/roku_apps.xml") }.getOrNull()
        if (xml != null) {
            Regex("<app id=\"(\\d+)\"[^>]*>([^<]*)</app>").findAll(xml)
                .forEach { put(it.groupValues[1], it.groupValues[2].replace("&amp;", "&")) }
        }
        if (isEmpty()) { put("13", "Prime Video"); put("837", "YouTube") }
    }
    val keys = mutableListOf<String>()

    companion object {
        val VALID_KEYS = setOf(
            "Home", "Rev", "Fwd", "Play", "Select", "Left", "Right", "Down", "Up", "Back", "InstantReplay",
            "Info", "Backspace", "Search", "Enter", "VolumeUp", "VolumeDown", "VolumeMute", "PowerOff", "PowerOn",
            "ChannelUp", "ChannelDown", "InputTuner", "InputHDMI1", "InputHDMI2", "InputHDMI3", "InputHDMI4", "InputAV1",
        )
        const val LIMITED_MESSAGE = "ECP command not allowed in Limited mode."
    }

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = handle(request)
        }
        server.start()
    }

    override fun close() = server.shutdown()

    private fun handle(req: RecordedRequest): MockResponse {
        val path = req.path ?: return MockResponse().setResponseCode(404)
        return when {
            req.method == "GET" && path == "/" -> {
                val fixture = Fixtures.text("roku/roku_root.xml")
                xml(200, fixture.substring(fixture.indexOf("<?xml")))
            }
            req.method == "GET" && path == "/query/device-info" -> xml(200, Fixtures.text("roku/roku_device_info.xml"))
            req.method == "GET" && path == "/query/active-app" -> xml(
                200,
                """<?xml version="1.0" encoding="UTF-8" ?>
<active-app>
	<app id="$activeAppId" type="appl" version="1.0" ui-location="$activeAppId">$activeAppName</app>
</active-app>""",
            )
            req.method == "GET" && path == "/query/apps" -> {
                if (limitedMode) return limited()
                runCatching { Fixtures.text("roku/roku_apps.xml") }.getOrNull()?.let { return xml(200, it) }
                val items = apps.entries.joinToString("\n") { (id, name) -> "\t<app id=\"$id\" type=\"appl\" version=\"1.0\">$name</app>" }
                xml(200, "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n<apps>\n$items\n</apps>")
            }
            req.method == "POST" && path.startsWith("/keypress/") -> {
                if (limitedMode) return limited()
                val key = path.removePrefix("/keypress/")
                if (key !in VALID_KEYS && !key.startsWith("Lit_")) return MockResponse().setResponseCode(400)
                keys += key
                MockResponse().setResponseCode(200)
            }
            req.method == "POST" && path.startsWith("/launch/") -> {
                if (limitedMode) return limited()
                val id = path.removePrefix("/launch/").substringBefore("?")
                val name = apps[id] ?: return MockResponse().setResponseCode(404)
                activeAppId = id
                activeAppName = name
                MockResponse().setResponseCode(200)
            }
            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun limited() = MockResponse().setResponseCode(403)
        .setHeader("Content-Type", "text/plain").setBody(Fixtures.text("roku/roku_error_limited_mode.txt").trim())

    private fun xml(status: Int, body: String) =
        MockResponse().setResponseCode(status).setHeader("Content-Type", "text/xml; charset=\"utf-8\"").setBody(body.trim())
}
