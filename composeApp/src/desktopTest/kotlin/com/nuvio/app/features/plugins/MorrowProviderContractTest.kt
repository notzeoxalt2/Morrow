package com.nuvio.app.features.plugins

import com.nuvio.app.features.plugins.runtime.PluginRuntime
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MorrowProviderContractTest {
    @Test
    fun liveMiruroAdapterUsesMorrowMetadataAndRuntime() = runBlocking {
        assumeTrue("Live verification is opt-in", System.getenv("MORROW_LIVE_PROVIDER_TEST") == "1")
        val file = File(System.getenv("MORROW_MIRURO_SCRIPT") ?: "missing-miruro-script")
        assertTrue(file.isFile, "Provide the built Miruro adapter")
        val results = PluginRuntime.executePlugin(
            code = file.readText(), tmdbId = "1429", mediaType = "tv", season = 1,
            episode = 1, scraperId = "morrow-miruro-live", respectSearchPause = false,
        )
        assertTrue(results.isNotEmpty(), "Live Attack on Titan S1 E1 returned no streams")
        assertTrue(results.all { it.provider == "Miruro" && it.title.contains("S1 E1") })
        assertTrue(results.any { it.language == "ja" }, "Missing SUB sources")
        assertTrue(results.any { it.language == "en" }, "Missing DUB sources")
        assertTrue(results.all { it.type in setOf("m3u8", "mp4", "mpd") })
        println("Live Morrow QuickJS Miruro: ${results.size} sources; languages=${results.map { it.language }.toSet()}")
        val splitSeason = PluginRuntime.executePlugin(
            code = file.readText(), tmdbId = "1429", mediaType = "tv", season = 3,
            episode = 13, scraperId = "morrow-miruro-live-split-season", respectSearchPause = false,
        )
        assertTrue(splitSeason.isNotEmpty(), "Attack on Titan S3 E13 must map to Part 2 E1")
        assertTrue(splitSeason.all { it.title.contains("S3 E13") })
        println("Live Morrow QuickJS Miruro split season S3 E13: ${splitSeason.size} sources")
    }

    @Test
    fun miruroAdapterUsesMorrowRuntimeAndRejectsWrongEpisode() = runBlocking {
        val file = File(System.getenv("MORROW_MIRURO_SCRIPT") ?: "missing-miruro-script")
        assumeTrue("Set MORROW_MIRURO_SCRIPT to the built adapter for integration verification", file.isFile)
        val catalog = """{"data":[{"id":"wrong-title","title":{"english":"Naruto Shippuden"},"format":"TV","episode_count":500},{"id":"correct-title","title":{"english":"Naruto"},"format":"TV","episode_count":220}]}"""
        val playback = """{"episode_number":7,"tracks":[{"track":"sub","providers":[{"provider":"actual-provider","subtitles":[{"file":"https://example.test/7.vtt","language":"en","label":"English"}],"servers":[{"server":"actual-server","headers":{"Referer":"https://example.test/"},"streams":[{"url":"https://example.test/7.m3u8","format":"hls","quality":"720p"}]}]}]}]}"""
        val upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        upstream.createContext("/") { exchange ->
            val isSearch = exchange.requestURI.path == "/api/v1/anime"
            val json = if (isSearch) catalog else playback
            val compressed = ByteArrayOutputStream().also { out ->
                GZIPOutputStream(out).use { it.write(json.toByteArray()) }
            }.toByteArray()
            val key = "miruro/catalog".toByteArray()
            for (i in compressed.indices) compressed[i] = (compressed[i].toInt() xor key[i % key.size].toInt()).toByte()
            exchange.responseHeaders.add("Content-Type", "application/octet-stream")
            exchange.sendResponseHeaders(200, compressed.size.toLong())
            exchange.responseBody.use { it.write(compressed) }
            exchange.close()
        }
        upstream.start()
        try {
            val code = file.readText().replace("https://www.miruro.to", "http://127.0.0.1:${upstream.address.port}")
            suspend fun execute(episode: Int) = PluginRuntime.executePlugin(
                code = code, tmdbId = "Naruto", mediaType = "tv", season = 1,
                episode = episode, scraperId = "morrow-miruro-contract", respectSearchPause = false,
            )
            val result = execute(7).single()
            assertEquals("https://example.test/7.m3u8", result.url)
            assertEquals("ja", result.language)
            assertEquals("https://example.test/", result.headers?.get("Referer"))
            assertEquals("https://example.test/7.vtt", result.subtitles?.single()?.url)
            assertEquals(0, execute(8).size)
        } finally {
            upstream.stop(0)
        }
    }
}
