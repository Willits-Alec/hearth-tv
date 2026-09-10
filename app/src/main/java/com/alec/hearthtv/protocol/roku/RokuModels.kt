package com.alec.hearthtv.protocol.roku

data class RokuApp(val id: String, val name: String, val type: String, val version: String)

data class RokuDeviceInfo(
    val modelName: String,
    val modelNumber: String,
    /** The name the owner gave the box, else the model. */
    val friendlyName: String,
    val softwareVersion: String,
    val powerMode: String,
    /** "default" or "limited" as the box reports it; a 403 on a control call is the authoritative signal. */
    val ecpSettingMode: String,
)

/** What is in front on the Roku; [isHome] when the home screen or screensaver is showing. */
data class RokuActiveApp(val id: String, val name: String, val type: String) {
    val isHome: Boolean get() = type == "home"
}

sealed class RokuException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Unreachable(cause: Throwable) : RokuException("Can't reach the Roku", cause)
    /** Network access is set to Limited on the box: reads work, control is refused (SCOPE.md §3). */
    class LimitedMode : RokuException(LIMITED_HINT)
    class BadResponse(val code: Int, detail: String) : RokuException("The Roku answered $code: $detail")

    companion object {
        const val LIMITED_HINT = "The Roku is refusing control from apps. On the Roku: Settings → System → Advanced system settings → Control by mobile apps → Network access → Default."
    }
}
