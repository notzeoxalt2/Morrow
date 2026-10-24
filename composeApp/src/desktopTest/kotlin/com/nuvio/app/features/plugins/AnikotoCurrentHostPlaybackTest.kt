package com.nuvio.app.features.plugins

import com.nuvio.app.features.player.LocalStreamProxy
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnikotoCurrentHostPlaybackTest {
    @Test fun realNarutoServerRosterDecodesThroughMorrow() = runBlocking {
        assumeTrue(System.getenv("MORROW_LIVE_ROLLOUT") == "1")
        val code=File(requireNotNull(System.getenv("MORROW_ANIME_REPO")),"providers/anikototv.js").readText()
        val scraper=PluginScraper(id="anikoto-current-live",repositoryUrl="https://example.test/manifest.json",
            name="Anikoto",description="Actual site",version="test",filename="anikototv.js",
            supportedTypes=listOf("anime"),enabled=true,manifestEnabled=true,code=code)
        val streams=PluginRepository.executeScraper(scraper,"tt0409591","series",1,1).getOrThrow()
        assertEquals(6,streams.size,"Actual Naruto roster has three SUB and three DUB servers")
        assertEquals(setOf("ja","en"),streams.map { it.language }.toSet())
        for(stream in streams){
            assertTrue(stream.title.contains("Naruto")&&stream.title.contains("Episode 1"))
            val process=ProcessBuilder("python",requireNotNull(System.getenv("MORROW_NATIVE_AUDIO_DECODE_SCRIPT")),
                requireNotNull(System.getenv("MORROW_NATIVE_MPV_DLL")),LocalStreamProxy.wrapUrl(stream.url,stream.headers)).start()
            val finished=process.waitFor(45,TimeUnit.SECONDS)
            if(!finished)process.destroyForcibly()
            assertTrue(finished,"Decoder timeout: ${stream.name}")
            val decoded=process.inputStream.bufferedReader().readText().trim()
            assertEquals(0,process.exitValue(),"${stream.name}: $decoded")
            assertTrue(decoded.contains("\"audioChannels\": \""),decoded)
            println("ANIKOTO-CURRENT ${stream.name} $decoded")
        }
    }
}
