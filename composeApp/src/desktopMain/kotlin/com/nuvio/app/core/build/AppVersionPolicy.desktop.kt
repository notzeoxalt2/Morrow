package com.nuvio.app.core.build

actual object AppVersionPolicy {
    actual val displayVersionName: String = AppVersionConfig.DESKTOP_VERSION_NAME
    actual val displayVersionCode: Int = AppVersionConfig.DESKTOP_VERSION_CODE
    actual val basedOnVersionName: String? = null
    actual val userAgentAppName: String = "MorrowDesktop"
}
