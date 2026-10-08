package com.nuvio.app.features.plugins

import com.nuvio.app.features.player.LocalStreamProxy
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HiAnimeSitePlaybackTest {
    @Test
    fun realSiteEpisodesDecodeThroughMorrowRuntimeAndProxy() = runBlocking {
        assumeTrue(System.getenv("MORROW_LIVE_PROVIDER_TEST") == "1")
        val code = File(requireNotNull(System.getenv("MORROW_HIANIME_SITE_SCRIPT"))).readText()
        val scraper = PluginScraper(id = "hianime-site", repositoryUrl = "https://example.test/manifest.json",
            name = "HiAnime", description = "Actual site contract", version = "test", filename = "hianime-site.js",
            supportedTypes = listOf("anime"), enabled = true, manifestEnabled = true, code = code)
        val decoder = requireNotNull(System.getenv("MORROW_NATIVE_DECODE_SCRIPT"))
        val library = requireNotNull(System.getenv("MORROW_NATIVE_MPV_DLL"))
        for ((lookup, episode) in listOf("mal:16498" to 1, "anilist:21" to 1, "anilist:21" to 2, "mal:34566" to 3)) {
            val streams = PluginRepository.executeScraper(scraper, lookup, "anime", 1, episode).getOrThrow()
            assertEquals(setOf("ja", "en"), streams.map { it.language }.toSet(), "$lookup E$episode must return genuine SUB and DUB")
            for (stream in streams) {
                assertTrue(stream.title.contains("Episode $episode"))
                val proxy = LocalStreamProxy.wrapUrl(stream.url, stream.headers)
                val process = ProcessBuilder("python", decoder, library, proxy).start()
                val completed = process.waitFor(45, TimeUnit.SECONDS)
                if (!completed) process.destroyForcibly()
                assertTrue(completed, "Native decoder timed out")
                val result = process.inputStream.bufferedReader().readText().trim()
                assertEquals(0, process.exitValue(), "$lookup E$episode ${stream.language}: $result")
                println("HiAnime genuine site $lookup E$episode ${stream.language}: $result")
            }
        }
    }
}
