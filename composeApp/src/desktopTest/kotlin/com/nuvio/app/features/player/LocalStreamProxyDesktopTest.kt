package com.nuvio.app.features.player

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalStreamProxyDesktopTest {
    @Test fun wrappedSegmentsHaveCorrectMimeLengthAndTransportBytes() {
        val upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val png = byteArrayOf(0x89.toByte(),0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a) +
            ByteArray(12) + "IEND".encodeToByteArray() + ByteArray(4)
        val ts = ByteArray(188 * 4).also { for (i in 0..3) it[i*188] = 0x47 }
        upstream.createContext("/") { exchange ->
            val payload = png + ts
            exchange.responseHeaders.add("Content-Type", "image/png")
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
            exchange.close()
        }
        upstream.start()
        try {
            val connection = URI(LocalStreamProxy.wrapUrl("http://127.0.0.1:${upstream.address.port}/segment.png", mapOf("Referer" to "https://required.test/"))).toURL().openConnection()
            val bytes = connection.getInputStream().use { it.readBytes() }
            assertEquals("video/mp2t", connection.contentType)
            assertEquals(ts.size.toLong(), connection.contentLengthLong)
            assertTrue(bytes.contentEquals(ts))
        } finally { upstream.stop(0) }
    }
    @Test fun extensionlessPlaylistWithImageMimeKeepsSegmentHeaders() {
        val upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        upstream.createContext("/") { exchange ->
            val valid = exchange.requestHeaders.getFirst("Referer") == "https://required.test/"
            val payload = if (exchange.requestURI.path == "/cdn/opaque")
                "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nsegment.ts\n#EXT-X-ENDLIST\n".toByteArray()
                else byteArrayOf(0x47, 0x40, 0x01, 0x10)
            exchange.responseHeaders.add("Content-Type", "image/jpeg")
            exchange.sendResponseHeaders(if (valid) 200 else 403, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
            exchange.close()
        }
        upstream.start()
        try {
            val url = LocalStreamProxy.wrapUrl("http://127.0.0.1:${upstream.address.port}/cdn/opaque?t.m3u8", mapOf("Referer" to "https://required.test/"))
            val connection = URI(url).toURL().openConnection()
            val playlist = connection.getInputStream().bufferedReader().use { it.readText() }
            assertTrue(connection.contentType.contains("mpegurl"))
            val segment = playlist.lineSequence().first { it.startsWith("http://127.0.0.1:") }
            assertTrue(URI(segment).toURL().readBytes().contentEquals(byteArrayOf(0x47, 0x40, 0x01, 0x10)))
        } finally { upstream.stop(0) }
    }

    @Test fun qualityChangeRetainsSourceHeadersAndFiltersMaster() {
        val upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        upstream.createContext("/") { exchange ->
            val valid = exchange.requestHeaders.getFirst("Referer") == "https://required.test/" &&
                exchange.requestHeaders.getFirst("X-Morrow-Video-Quality") == null
            val playlist = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720\n720.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=8000000,RESOLUTION=1920x1080\n1080.m3u8"
            val payload = playlist.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/vnd.apple.mpegurl")
            exchange.sendResponseHeaders(if (valid) 200 else 403, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
            exchange.close()
        }
        upstream.start()
        try {
            val wrapped = LocalStreamProxy.wrapUrl("http://127.0.0.1:${upstream.address.port}/master.m3u8", mapOf("Referer" to "https://required.test/"))
            val maximum = URL(LocalStreamProxy.withVideoQuality(wrapped, emptyMap(), VideoQuality.Max)).readText()
            assertTrue(maximum.contains("1080.m3u8"))
            assertTrue(!maximum.contains("720.m3u8"))
            val capped = URL(LocalStreamProxy.withVideoQuality(wrapped, emptyMap(), VideoQuality.High)).readText()
            assertTrue(capped.contains("720.m3u8"))
            assertTrue(!capped.contains("1080.m3u8"))
        } finally { upstream.stop(0) }
    }

    @Test
    fun encodedPlaylistsDecodeBeforeRewriteWithoutLeakingDirectiveToHost() {
        val key = ByteArray(32) { (it + 17).toByte() }
        val marker = java.util.Base64.getEncoder().encodeToString(key)
        val upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        upstream.createContext("/") { exchange ->
            if (exchange.requestHeaders.getFirst("X-Morrow-Playlist-Xor") != null) {
                exchange.sendResponseHeaders(403, -1)
            } else {
                val path = exchange.requestURI.path
                val plain = if (path == "/master.m3u8") "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nvariant.m3u8\n"
                    else "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nsegment.ts\n#EXT-X-ENDLIST\n"
                val payload = if (path.endsWith(".m3u8")) {
                    val bytes = plain.toByteArray()
                    for (i in bytes.indices) bytes[i] = (bytes[i].toInt() xor key[i % key.size].toInt()).toByte()
                    java.util.Base64.getEncoder().encode(bytes)
                } else byteArrayOf(0x47, 1, 2, 3)
                exchange.responseHeaders.add("Content-Type", "application/octet-stream")
                exchange.sendResponseHeaders(200, payload.size.toLong())
                exchange.responseBody.use { it.write(payload) }
            }
            exchange.close()
        }
        upstream.start()
        try {
            val url = "http://127.0.0.1:${upstream.address.port}/master.m3u8"
            val wrapped = LocalStreamProxy.wrapUrl(url, mapOf("X-Morrow-Playlist-Xor" to marker))
            val master = URL(wrapped).readText()
            assertTrue(master.startsWith("#EXTM3U"))
            val variant = master.lines().first { it.startsWith("http://") }
            val media = URL(variant).readText()
            assertTrue(media.startsWith("#EXTM3U"))
            val segment = media.lines().first { it.startsWith("http://") }
            assertTrue(URL(segment).readBytes().contentEquals(byteArrayOf(0x47, 1, 2, 3)))
            val bad = LocalStreamProxy.wrapUrl(url, mapOf("X-Morrow-Playlist-Xor" to "invalid"))
            val connection = URL(bad).openConnection() as java.net.HttpURLConnection
            assertEquals(502, connection.responseCode)
            connection.disconnect()
        } finally { upstream.stop(0) }
    }

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
