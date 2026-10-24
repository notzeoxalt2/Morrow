package com.nuvio.app.features.streams

import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import com.nuvio.app.features.addons.RawHttpResponse
import com.nuvio.app.features.addons.httpRequestRaw
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlin.time.TimeSource

internal enum class SourceReadiness(val label: String) {
    CHECKING("Checking media URL…"),
    REACHABLE("● Media URL responds"),
    FAILED("● Media URL unavailable"),
    UNKNOWN("○ Could not verify"),
    RESOLVE("○ Checked when resolved"),
}

// An HTTP media response is not a guarantee that this device can decode it.
internal fun classifySourceResponse(response: RawHttpResponse): SourceReadiness {
    if (response.status !in 200..299) return SourceReadiness.FAILED
    val type = response.headers.entries.firstOrNull { it.key.equals("content-type", true) }?.value.orEmpty().lowercase()
    val text = response.body.trimStart()
    if (type.contains("text/html") || text.startsWith("<!doctype html", true) || text.startsWith("<html", true)) return SourceReadiness.FAILED
    if (text.startsWith("#EXTM3U") || text.contains("<MPD") || type.startsWith("video/") || type.contains("mpegurl") || type.contains("dash+xml")) return SourceReadiness.REACHABLE
    return SourceReadiness.UNKNOWN
}

internal object SourceReadinessChecks {
    private data class Cached(val value: SourceReadiness, val time: kotlin.time.TimeMark)
    private val cache = linkedMapOf<String, Cached>()
    private val mutex = Mutex()
    private val requests = Semaphore(4)
    suspend fun check(stream: StreamItem): SourceReadiness {
        val url = stream.playableDirectUrl ?: return SourceReadiness.RESOLVE
        if (!url.startsWith("https://") && !url.startsWith("http://")) return SourceReadiness.UNKNOWN
        if (stream.clientResolve != null) return SourceReadiness.RESOLVE
        val headers = stream.behaviorHints.proxyHeaders?.request.orEmpty()
        val key = url + headers.entries.sortedBy { it.key }.joinToString { it.key + it.value }
        mutex.withLock { cache[key]?.takeIf { it.time.elapsedNow().inWholeSeconds < 60 }?.let { return it.value } }
        val result = requests.withPermit {
            try {
                withTimeoutOrNull(2_500) {
                    val response = httpRequestRaw("GET", url,
                        headers.filterKeys { !it.equals("Range", true) } + ("Range" to "bytes=0-2047"),
                        "", maxResponseBodyBytes = 2048)
                    classifySourceResponse(response)
                } ?: SourceReadiness.UNKNOWN
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { SourceReadiness.UNKNOWN }
        }
        mutex.withLock {
            if (cache.size >= 128) cache.remove(cache.keys.first())
            cache[key] = Cached(result, TimeSource.Monotonic.markNow())
        }
        return result
    }
}

@Composable
internal fun SourceReadinessBadge(stream: StreamItem) {
    var status by remember(stream.playableDirectUrl, stream.behaviorHints.proxyHeaders?.request) { mutableStateOf(SourceReadiness.CHECKING) }
    LaunchedEffect(stream.playableDirectUrl, stream.behaviorHints.proxyHeaders?.request) { status = SourceReadinessChecks.check(stream) }
    Text(status.label, fontSize = 11.sp, color = when (status) {
        SourceReadiness.REACHABLE -> Color(0xFF83D9A3)
        SourceReadiness.FAILED -> Color(0xFFFFA6A6)
        else -> Color(0xFFA8A8A8)
    })
}
