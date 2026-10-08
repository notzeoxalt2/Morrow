package com.nuvio.app.features.plugins

import com.nuvio.app.features.plugins.runtime.PluginRuntime
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MorrowProviderRolloutTest {
    @Test fun liveEveryReturnedSubDubSourceForReportedProviders() = runBlocking {
        assumeTrue(System.getenv("MORROW_LIVE_ROLLOUT") == "1")
        val names = System.getenv("MORROW_ROLLOUT_ANIME")?.split(',').orEmpty()
        val decoder = requireNotNull(System.getenv("MORROW_NATIVE_DECODE_SCRIPT"))
        val library = requireNotNull(System.getenv("MORROW_NATIVE_MPV_DLL"))
        val failures = mutableListOf<String>()
        for (name in names) {
            val file = script("MORROW_ANIME_REPO", name)
            val scraper = PluginScraper(id = "audio-check-$name", repositoryUrl = "https://example.test/anime.json",
                name = name, description = "Live audio verification", version = "test", filename = "$name.js",
                supportedTypes = listOf("anime"), enabled = true, manifestEnabled = true, code = file.readText())
            val sources = PluginRepository.executeScraper(scraper, "mal:34566", "anime", 1, 3).getOrThrow()
            if (sources.isEmpty()) failures += "$name Boruto E3 returned no sources"
            for (source in sources) {
                val url = com.nuvio.app.features.player.LocalStreamProxy.wrapUrl(source.url, source.headers)
                val process = ProcessBuilder("python", decoder, library, url).start()
                val finished = process.waitFor(45, java.util.concurrent.TimeUnit.SECONDS)
                if (!finished) process.destroyForcibly()
                val result = if (finished) process.inputStream.bufferedReader().readText().trim() else "timeout"
                val decoded = finished && process.exitValue() == 0
                println("AUDIO-CHECK $name Boruto E3 ${source.name}: ${if (decoded) "PASS" else "FAIL"} $result")
                if (!decoded) failures += "$name ${source.name}: $result"
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
    @Test fun liveFetchDiagnostics() = runBlocking {
        assumeTrue(System.getenv("MORROW_LIVE_ROLLOUT") == "1")
        val code = """
            module.exports.getStreams = async function() {
                for (const endpoint of ['https://api.themoviedb.org/3/tv/108978?api_key=' + TMDB_API_KEY, 'https://chad.anidap.lol/rest/api/episodes?id=21']) {
                    const response = await fetch(endpoint);
                    const body = await response.text();
                    console.log('FETCH-DIAGNOSTIC', response.status, response.headers.get('content-type'), body.length, body.slice(0, 100));
                }
                return [];
            };
        """.trimIndent()
        PluginRuntime.executePlugin(code, "108978", "tv", 1, 1, "diagnostic", respectSearchPause = false)
        Unit
    }
    private fun script(repo: String, name: String): File = File(System.getenv(repo) ?: "missing-repo", "providers/$name.js")

    @Test fun catalogResolverRejectsDifferentTitleAndUnavailableEpisodeInQuickJs() = runBlocking {
        val file = script("MORROW_ANIME_REPO", "anidap")
        assumeTrue(file.isFile)
        val upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        upstream.createContext("/") { exchange ->
            val body = when (exchange.requestURI.path) {
                "/api/anime/search" -> """{"results":[{"id":"1735","title":{"english":"Naruto Shippuden"},"type":"TV"},{"id":"20","title":{"english":"Naruto"},"type":"TV"}]}"""
                "/api/anime/20" -> """{"data":{"id":"correct-naruto","anilistId":20,"titleEnglish":"Naruto","format":"TV"}}"""
                "/rest/api/episodes" -> "{\"episodes\":[{\"number\":7}],\"padding\":\"" + "x".repeat(1_400_000) + "\"}"
                "/rest/api/servers" -> """{"subProviders":[{"id":"actual-server"}],"dubProviders":[]}"""
                "/rest/api/sources" -> """{"sources":[{"url":"http://127.0.0.1:${upstream.address.port}/7.m3u8","quality":"720p"}],"headers":{"Referer":"https://actual-player.test/"}}"""
                "/7.m3u8" -> "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\n7.ts\n#EXT-X-ENDLIST\n"
                "/7.ts" -> "synthetic transport segment for resolver contract"
                else -> "{}"
            }
            exchange.responseHeaders.add("Content-Type", if (exchange.requestURI.path == "/7.ts") "video/mp2t" else "application/json")
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
            exchange.close()
        }
        upstream.start()
        try {
            val local = "http://127.0.0.1:${upstream.address.port}"
            val code = file.readText().replace("https://anidap.lol", local).replace("https://chad.anidap.lol", local)
            suspend fun execute(title: String, ep: Int) = PluginRuntime.executePlugin(code, title, "tv", 1, ep, "catalog-contract", respectSearchPause = false)
            val stream = execute("Naruto", 7).single()
            assertEquals("Anidap", stream.provider)
            assertEquals("Naruto · Episode 7", stream.title)
            assertEquals("https://actual-player.test/", stream.headers?.get("Referer"))
            assertTrue(execute("Naruto", 8).isEmpty())
            assertTrue(execute("Naruto Shippuden sequel missing", 7).isEmpty())
        } finally { upstream.stop(0) }
    }

    @Test fun liveNewAnimeAdaptersUseRepositoryIdentityAndNativePlayback() = runBlocking {
        assumeTrue(System.getenv("MORROW_LIVE_ROLLOUT") == "1")
        val names = System.getenv("MORROW_ROLLOUT_ANIME")?.split(',').orEmpty()
        assertTrue(names.isNotEmpty())
        val failures = mutableListOf<String>()
        for (name in names) {
            val file = script("MORROW_ANIME_REPO", name)
            assertTrue(file.isFile)
            val scraper = PluginScraper(id = "rollout-$name", repositoryUrl = "https://example.test/anime.json",
                name = name, description = "Live rollout", version = "test", filename = "$name.js", supportedTypes = listOf("anime"),
                enabled = true, manifestEnabled = true, code = file.readText())
            val samples = if (name in setOf("animeheaven", "reanime", "senshi", "anime-nexus")) listOf("anilist:21" to 1)
                else listOf("anilist:21" to 1, "mal:34566" to 3)
            for ((lookup, episode) in samples) {
                try {
                val sources = PluginRepository.executeScraper(scraper, lookup, "anime", 1, episode).getOrThrow()
                assertTrue(sources.isNotEmpty(), "$name $lookup E$episode returned no native sources")
                assertTrue(sources.all { it.title.contains(if (lookup == "anilist:21") "Piece" else "Boruto", true) })
                assertTrue(sources.all { it.type in setOf("m3u8", "mp4", "mpd") })
                println("ROLLOUT $name $lookup E$episode: ${sources.size} sources; ${sources.map { it.name }}")
                decodeAtLeastOne(sources, "$name $lookup E$episode")
                } catch (error: Throwable) { failures += "$name $lookup E$episode: ${error.message}"; println("ROLLOUT-FAILED ${failures.last()}") }
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test fun liveMovieAdaptersKeepExactShowSeasonEpisode() = runBlocking {
        assumeTrue(System.getenv("MORROW_LIVE_ROLLOUT") == "1")
        val names = System.getenv("MORROW_ROLLOUT_MOVIES")?.split(',').orEmpty()
        assertTrue(names.isNotEmpty())
        val failures = mutableListOf<String>()
        for (name in names) {
            val file = script("MORROW_MOVIES_REPO", name)
            assertTrue(file.isFile)
            for ((id, season, episode) in listOf(Triple("108978", 1, 1), Triple("108978", 2, 1), Triple("550", 0, 0))) {
                try {
                val type = if (id == "550") "movie" else "tv"
                val sources = PluginRuntime.executePlugin(file.readText(), id, type, season.takeIf { type == "tv" },
                    episode.takeIf { type == "tv" }, "movie-rollout-$name", respectSearchPause = false)
                assertTrue(sources.isNotEmpty(), "$name $id S$season E$episode returned no sources")
                assertTrue(sources.all { it.title.contains(if (type == "tv") "Reacher" else "Fight Club", true) })
                if (type == "tv") assertTrue(sources.all { it.title.contains("S$season E$episode") })
                println("ROLLOUT $name $id S$season E$episode: ${sources.size} sources; ${sources.map { it.name }}")
                decodeAtLeastOne(sources, "$name $id S$season E$episode")
                } catch (error: Throwable) { failures += "$name $id S$season E$episode: ${error.message}"; println("ROLLOUT-FAILED ${failures.last()}") }
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    private fun decodeAtLeastOne(sources: List<PluginRuntimeResult>, label: String) {
        val decodeScript = System.getenv("MORROW_NATIVE_DECODE_SCRIPT") ?: return
        val library = System.getenv("MORROW_NATIVE_MPV_DLL") ?: return
        val errors = mutableListOf<String>()
        for (source in sources.take(3)) {
            val url = com.nuvio.app.features.player.LocalStreamProxy.wrapUrl(source.url, source.headers)
            val process = ProcessBuilder("python", decodeScript, library, url).start()
            val finished = process.waitFor(45, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            val result = if (finished) process.inputStream.bufferedReader().readText().trim() else "timeout"
            if (finished && process.exitValue() == 0) {
                println("DECODE $label ${source.name}: $result")
                return
            }
            errors += "${source.name}: $result"
        }
        assertTrue(false, "$label: no selected source decoded: $errors")
    }
}
