package com.nuvio.app.features.player

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalStreamProxyDesktopTest {
    @Test
    fun redirectedMasterKeepsAudioSubtitlesKeysAndRangesBehindProxy() {
        val upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        upstream.createContext("/") { exchange ->
            if (exchange.requestHeaders.getFirst("Referer") != "https://example.test/") {
                exchange.sendResponseHeaders(403, -1)
            } else if (exchange.requestURI.path == "/start.m3u8") {
                exchange.responseHeaders.add("Location", "/media/master.m3u8")
                exchange.sendResponseHeaders(302, -1)
            } else {
                val path = exchange.requestURI.path
                exchange.responseHeaders.add("X-Upstream-Path", path)
                val payload = if (path.endsWith("master.m3u8")) {
                    """#EXTM3U
                        |#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",URI="audio/index.m3u8"
                        |#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",URI="subs/index.m3u8"
                        |#EXT-X-SESSION-KEY:METHOD=AES-128,URI="key.bin"
                        |#EXT-X-STREAM-INF:BANDWIDTH=1000,AUDIO="audio",SUBTITLES="subs"
                        |video/index.m3u8
                    """.trimMargin().toByteArray()
                } else {
                    path.toByteArray()
                }
                exchange.responseHeaders.add("Content-Type", if (path.endsWith("master.m3u8")) "application/vnd.apple.mpegurl" else "application/octet-stream")
                val ranged = exchange.requestHeaders.getFirst("Range") == "bytes=0-3"
                val body = if (ranged) payload.copyOfRange(0, 4) else payload
                if (ranged) exchange.responseHeaders.add("Content-Range", "bytes 0-3/${payload.size}")
                exchange.sendResponseHeaders(if (ranged) 206 else 200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            exchange.close()
        }
        upstream.start()
        try {
            val source = "http://127.0.0.1:${upstream.address.port}/start.m3u8"
            // wrapUrl deliberately leaves loopback unchanged, so use its URL encoder via a remote target,
            // then substitute only the encoded target for this local deterministic upstream fixture.
            val template = LocalStreamProxy.wrapUrl("https://example.test/start.m3u8", mapOf("Referer" to "https://example.test/"))
            val wrapped = template.replace(java.net.URLEncoder.encode("https://example.test/start.m3u8", "UTF-8"), java.net.URLEncoder.encode(source, "UTF-8"))
            val master = URL(wrapped).readText()
            val references = Regex("URI=\"([^\"]+)\"").findAll(master).map { it.groupValues[1] }.toList() + master.lines().filter { it.isNotBlank() && !it.startsWith("#") }
            assertEquals(4, references.size)
            for (reference in references) {
                assertEquals("127.0.0.1", URI(reference).host)
                val conn = URL(reference).openConnection() as java.net.HttpURLConnection
                assertEquals(200, conn.responseCode)
                val requestedTarget = reference.substringAfter("url=").substringBefore("&h=")
                assertTrue(java.net.URLDecoder.decode(requestedTarget, "UTF-8").contains("/media/"))
                conn.inputStream.use { it.readBytes() }
                conn.disconnect()
            }
            val range = URL(references[2]).openConnection() as java.net.HttpURLConnection
            range.setRequestProperty("Range", "bytes=0-3")
            assertEquals(206, range.responseCode)
            assertEquals(4, range.inputStream.readBytes().size)
            assertTrue(range.getHeaderField("Content-Range").startsWith("bytes 0-3/"))
            range.disconnect()
        } finally {
            upstream.stop(0)
        }
    }
}
