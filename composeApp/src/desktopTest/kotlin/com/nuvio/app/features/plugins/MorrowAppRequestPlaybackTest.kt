package com.nuvio.app.features.plugins

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertTrue

class MorrowAppRequestPlaybackTest {
    @Test fun onePieceFromImdbCatalogReachesMiruroAndAnikage() = runBlocking {
        assumeTrue(System.getenv("MORROW_LIVE_ROLLOUT") == "1")
        val failures = mutableListOf<String>()
        for (name in listOf("miruro", "anikage")) {
            val code = File(requireNotNull(System.getenv("MORROW_ANIME_REPO")), "providers/$name.js").readText()
            val scraper = PluginScraper(id = "app-request-$name", repositoryUrl = "https://example.test/anime.json",
                name = name, description = "Actual catalog request", version = "test", filename = "$name.js",
                supportedTypes = listOf("anime"), enabled = true, manifestEnabled = true, code = code)
            for (episode in 1..2) {
                val result = PluginRepository.executeScraper(scraper, "tt0388629", "series", 1, episode)
                val sources = result.getOrDefault(emptyList())
                println("APP-REQUEST $name One Piece S1E$episode: "+sources.size+" sources; languages="+sources.map { it.language }.toSet())
                if (sources.isEmpty()) failures += "$name S1E$episode returned no streams via IMDb catalog request"
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
}
