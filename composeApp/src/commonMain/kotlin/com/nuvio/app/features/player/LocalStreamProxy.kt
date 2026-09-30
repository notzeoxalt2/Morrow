package com.nuvio.app.features.player

expect object LocalStreamProxy {
    fun start(): Int
    fun getPort(): Int
    fun isRunning(): Boolean
    fun wrapUrl(targetUrl: String, headers: Map<String, String>?): String
}
