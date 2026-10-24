package com.nuvio.app.features.plugins

import com.nuvio.app.features.player.LocalStreamProxy
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnimeWorldCurrentHostPlaybackTest {
    @Test fun narutoFirstEpisodeResolvesAndDecodesWithSelectedAudio() = runBlocking {
        assumeTrue(System.getenv("MORROW_LIVE_ROLLOUT") == "1")
        val code = File(requireNotNull(System.getenv("MORROW_ANIME_REPO")), "providers/animeworld.js").readText() +
            (System.getenv("MORROW_NATIVE_TRACE")?.let { "\n" + File(it).readText() } ?: "")
        val scraper = PluginScraper(id="world-current-live", repositoryUrl="https://example.test/manifest.json",
            name="AnimeWorld", description="Actual site", version="test", filename="animeworld.js",
            supportedTypes=listOf("anime"), enabled=true, manifestEnabled=true, code=code)
        val streams = PluginRepository.executeScraper(scraper, "tt0409591", "series", 1, 1).getOrThrow()
        assertTrue(streams.none { it.provider == "NativeDiagnostic" }, streams.firstOrNull()?.title ?: "No trace")
        assertTrue(streams.isNotEmpty(), "Naruto S1E1 returned no native source")
        val stream = streams.single { it.name?.contains("Multi-Audio") == true }
        assertTrue(stream.title.contains("Naruto") && stream.title.contains("S1 E1"))
        val url = LocalStreamProxy.wrapUrl(stream.url, stream.headers)
        for (language in listOf("hin", "eng", "jpn")) {
            val process = ProcessBuilder("python", requireNotNull(System.getenv("MORROW_NATIVE_AUDIO_DECODE_SCRIPT")),
                requireNotNull(System.getenv("MORROW_NATIVE_MPV_DLL")), url, language).start()
            val finished = process.waitFor(45, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            assertTrue(finished, "Decoder timeout: $language")
            val decoded = process.inputStream.bufferedReader().readText().trim()
            assertEquals(0, process.exitValue(), decoded)
            assertTrue(decoded.contains("\"selectedAudioLanguage\": \"$language\""), decoded)
            assertTrue(decoded.contains("\"audioChannels\": \""), decoded)
            println("WORLD-CURRENT Naruto S1E1 $language $decoded")
        }
    }
}
