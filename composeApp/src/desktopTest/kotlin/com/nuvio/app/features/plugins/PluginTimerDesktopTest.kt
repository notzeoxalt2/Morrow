package com.nuvio.app.features.plugins

import com.nuvio.app.features.plugins.runtime.PluginRuntime
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PluginTimerDesktopTest {
    @Test fun timersHonorDelayAndCancellation() = runBlocking {
        val code = """
            module.exports.getStreams = async function() {
                var cancelledFired = false;
                var cancelled = setTimeout(function() { cancelledFired = true; }, 20);
                clearTimeout(cancelled);
                var start = Date.now();
                await new Promise(function(resolve) { setTimeout(resolve, 100); });
                return [{url:'https://fixture.test/video.mp4',title:String(cancelledFired),quality:String(Date.now()-start)}];
            };
        """.trimIndent()
        val result = PluginRuntime.executePlugin(code, "1", "movie", null, null, "timer-fixture", respectSearchPause = false).single()
        assertEquals("false", result.title)
        assertTrue((result.quality?.toLongOrNull() ?: 0L) >= 80L, "Timer ran before its delay")
    }

    @Test fun requestWinnerCanClearTimeoutBeforeItFires() = runBlocking {
        val code = """
            module.exports.getStreams = async function() {
                var timer;
                var result = await Promise.race([
                    new Promise(function(resolve) { setTimeout(function(){resolve('request');},30); }),
                    new Promise(function(_,reject){timer=setTimeout(function(){reject(new Error('premature timeout'));},200);})
                ]);
                clearTimeout(timer);
                return [{url:'https://fixture.test/video.mp4',title:result}];
            };
        """.trimIndent()
        val result = PluginRuntime.executePlugin(code, "1", "movie", null, null, "timer-race-fixture", respectSearchPause = false).single()
        assertEquals("request", result.title)
    }
}
