package com.alec.hearthtv.remote

/**
 * When the TV stops answering at its saved address, look for it again: an SSDP search for Sony TVs on the Wi-Fi,
 * then the unauthenticated WOL-MAC read on each answer, and take the one whose MAC matches ours. This is how a
 * DHCP change (new router, expired lease) heals without the owner re-running setup (SCOPE.md §4.2).
 * Pinned by TvRelocatorTest.
 */
class TvRelocator(
    private val discover: suspend () -> List<String>,
    private val macOf: suspend (host: String) -> String?,
) {
    /** The TV's new host, or null when nothing on the network carries [expectedMac] at a different address. */
    suspend fun find(expectedMac: String, currentHost: String?): String? {
        val hosts = runCatching { discover() }.getOrDefault(emptyList()).distinct()
        for (host in hosts) {
            if (host == currentHost) continue
            val mac = runCatching { macOf(host) }.getOrNull() ?: continue
            if (mac.equals(expectedMac, ignoreCase = true)) return host
        }
        return null
    }
}
