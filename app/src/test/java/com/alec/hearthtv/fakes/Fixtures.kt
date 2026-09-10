package com.alec.hearthtv.fakes

/**
 * Fixture access. Every file under src/test/resources/fixtures is a literal reply captured from the real
 * devices (see fixtures/README.md), scrubbed of the owner's identifiers. Tests read them through here so a
 * missing or renamed fixture fails loudly instead of silently testing against hand-typed JSON.
 */
object Fixtures {
    fun text(path: String): String {
        val stream = Fixtures::class.java.classLoader?.getResourceAsStream("fixtures/$path")
            ?: error("missing fixture: fixtures/$path")
        return stream.bufferedReader().use { it.readText() }
    }

    fun list(subdir: String): List<String> {
        val url = Fixtures::class.java.classLoader?.getResource("fixtures/$subdir")
            ?: error("missing fixture folder: fixtures/$subdir")
        return java.io.File(url.toURI()).listFiles()?.map { it.name }?.sorted() ?: emptyList()
    }
}
