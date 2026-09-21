package com.nuvio.app.features.plugins.runtime.js

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.quickJs
import com.nuvio.app.features.plugins.runtime.configurePluginRuntime
import com.nuvio.app.features.plugins.runtime.pluginDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlin.concurrent.Volatile
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.coroutineContext

internal class JsRuntime {
    suspend fun <T> use(block: suspend QuickJs.() -> T): T {
        val dispatcher = (coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher)
            ?: pluginDispatcher
        return quickJs(dispatcher) {
            configurePluginRuntime()
            block()
        }
    }
}
