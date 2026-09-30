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
    fun liveKickAssAnimeUsesPublishedPlayerManifest() = runBlocking {
        assumeTrue(System.getenv("MORROW_LIVE_PROVIDER_TEST") == "1")
        val file = File(System.getenv("MORROW_KAA_SCRIPT") ?: "missing-kaa-script")
        assumeTrue(file.isFile)
        val streams = PluginRuntime.executePlugin(code = file.readText(), tmdbId = "1429", mediaType = "tv",
            season = 1, episode = 1, scraperId = "kaa-live", respectSearchPause = false)
        assertTrue(streams.isNotEmpty())
        assertTrue(streams.all { it.title.contains("Episode 1") && it.provider == "KickAssAnime" })
        val source = streams.first()
        val script = System.getenv("MORROW_NATIVE_DECODE_SCRIPT") ?: return@runBlocking
        val library = System.getenv("MORROW_NATIVE_MPV_DLL") ?: return@runBlocking
        val url = com.nuvio.app.features.player.LocalStreamProxy.wrapUrl(source.url, source.headers)
        val process = ProcessBuilder("python", script, library, url).start()
        val finished = process.waitFor(45, java.util.concurrent.TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        assertTrue(finished)
        val result = process.inputStream.bufferedReader().readText().trim()
        assertEquals(0, process.exitValue(), "KAA native decode failed: $result")
        println("KickAssAnime real manifest + native proxy/libmpv: $result")
    }

    @Test
    fun liveAnikageUsesExactAnimeAndNativeProxy() = runBlocking {
        assumeTrue("Live verification is opt-in", System.getenv("MORROW_LIVE_PROVIDER_TEST") == "1")
        val file = File(System.getenv("MORROW_ANIKAGE_SCRIPT") ?: "missing-anikage-script")
        assumeTrue(file.isFile)
        val scraper = PluginScraper(id = "anikage-live-mapping", repositoryUrl = "https://example.test/manifest.json",
            name = "Anikage", description = "Contract test", version = "test", filename = "anikage.js",
            supportedTypes = listOf("anime"), enabled = true, manifestEnabled = true, code = file.readText())
        for ((lookup, ep) in listOf("anilist:21" to 1, "mal:34566" to 3)) {
            val sources = PluginRepository.executeScraper(scraper, lookup, "anime", 1, ep).getOrThrow()
            assertTrue(sources.isNotEmpty(), "Anikage $lookup E$ep returned no validated sources")
            println("Anikage exact identity: $lookup E$ep "+sources.size+" validated sources")
            for (language in listOf("ja", "en")) {
                val source = sources.firstOrNull { it.language == language && it.name?.startsWith("Koto /") == true }
                    ?: sources.firstOrNull { it.language == language }
                if (source == null) {
                    println("Anikage $lookup E$ep: no validated $language source available")
                    continue
                }
                val decodeScript = System.getenv("MORROW_NATIVE_DECODE_SCRIPT") ?: continue
                val library = System.getenv("MORROW_NATIVE_MPV_DLL") ?: continue
                val url = com.nuvio.app.features.player.LocalStreamProxy.wrapUrl(source.url, source.headers)
                val process = ProcessBuilder("python", decodeScript, library, url).start()
                val finished = process.waitFor(45, java.util.concurrent.TimeUnit.SECONDS)
                if (!finished) process.destroyForcibly()
                assertTrue(finished, "Anikage decoder timed out")
                val result = process.inputStream.bufferedReader().readText().trim()
                assertEquals(0, process.exitValue(), "Anikage $lookup E$ep $language decode failed: $result")
                println("Anikage native proxy + libmpv: $lookup E$ep $language $result")
            }
        }
    }

    @Test
    fun liveAnimeIdsReachProviderThroughRepositoryConversion() = runBlocking {
        assumeTrue("Live verification is opt-in", System.getenv("MORROW_LIVE_PROVIDER_TEST") == "1")
        val file = File(System.getenv("MORROW_MIRURO_SCRIPT") ?: "missing-miruro-script")
        assertTrue(file.isFile)
        val scraper = PluginScraper(
            id = "miruro-live-mapping", repositoryUrl = "https://example.test/manifest.json",
            name = "Miruro", description = "Contract test", version = "test", filename = "miruro.js",
            supportedTypes = listOf("anime"), enabled = true, manifestEnabled = true, code = file.readText(),
        )
        for ((lookup, tmdb, title, ep) in listOf(
            listOf("anilist:21", "37854", "One Piece", "1"),
            listOf("kitsu:12", "37854", "One Piece", "1"),
            listOf("mal:34566", "70881", "Boruto", "3"),
        )) {
            assertEquals(tmdb, com.nuvio.app.features.tmdb.TmdbService.ensureTmdbId(lookup, "tv"))
            val streams = PluginRepository.executeScraper(scraper, lookup, "anime", 1, ep.toInt()).getOrThrow()
            assertTrue(streams.isNotEmpty(), "$lookup $title E$ep returned no sources through Morrow repository")
            assertTrue(streams.all { it.title.contains(title, ignoreCase = true) && it.title.contains("S1 E$ep") })
            println("Live repository conversion: $lookup -> TMDB $tmdb -> $title E$ep: ${streams.size} sources")
            val decodeScript = System.getenv("MORROW_NATIVE_DECODE_SCRIPT")
            val mpvLibrary = System.getenv("MORROW_NATIVE_MPV_DLL")
            if (decodeScript != null && mpvLibrary != null && !lookup.startsWith("kitsu:")) {
                val source = streams.first { it.name?.contains("vault") == true }
                val proxyUrl = com.nuvio.app.features.player.LocalStreamProxy.wrapUrl(source.url, source.headers)
                val process = ProcessBuilder("python", decodeScript, mpvLibrary, proxyUrl).start()
                val finished = process.waitFor(45, java.util.concurrent.TimeUnit.SECONDS)
                if (!finished) process.destroyForcibly()
                assertTrue(finished, "$title native decoder timed out")
                val decoded = process.inputStream.bufferedReader().readText().trim()
                assertEquals(0, process.exitValue(), "$title bundled decoder failed: $decoded")
                println("Bundled Morrow libmpv + native proxy: $title E$ep $decoded")
            }
        }
    }

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
        val catalog = """{"data":[{"id":"wrong-title","title":{"english":"Naruto Shippuden"},"format":"TV","episode_count":500},{"id":"correct-title","title":{"english":"Naruto"},"format":"TV","episode_count":null}]}"""
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
