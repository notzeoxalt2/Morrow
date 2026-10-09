package com.nuvio.app.features.plugins
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnimeSaltCurrentHostPlaybackTest {
    @Test fun currentMyStreamUsesCorrectCatalogEpisodeAndMultiAudio() = runBlocking {
        assumeTrue(System.getenv("MORROW_LIVE_ROLLOUT") == "1")
        val originalCode=File(requireNotNull(System.getenv("MORROW_ANIME_REPO")),"providers/animesalt.js").readText()
        val code = originalCode
        val scraper=PluginScraper(id="salt-current-live",repositoryUrl="https://example.test/manifest.json",name="AnimeSalt",description="Actual host",version="test",filename="animesalt.js",supportedTypes=listOf("anime"),enabled=true,manifestEnabled=true,code=code)
        for ((id,title) in listOf("tt0388629" to "One Piece", "tt0409591" to "Naruto")) {
            val sources=PluginRepository.executeScraper(scraper,id,"series",1,1).getOrThrow()
            assertTrue(sources.isNotEmpty(),"$title S1E1 current MyStream returned no native stream")
            val source=sources.single { it.name?.contains("Multi-Audio") == true }
            assertTrue(source.title.contains(title)&&source.title.contains("S1 E1"))
            for (language in listOf("eng","jpn")) {
                val url=com.nuvio.app.features.player.LocalStreamProxy.wrapUrl(source.url,source.headers)
                val process=ProcessBuilder("python",requireNotNull(System.getenv("MORROW_NATIVE_AUDIO_DECODE_SCRIPT")),requireNotNull(System.getenv("MORROW_NATIVE_MPV_DLL")),url,language).start()
                val finished=process.waitFor(45,TimeUnit.SECONDS)
                if(!finished)process.destroyForcibly()
                assertTrue(finished,"$title decoder timeout")
                val decoded=process.inputStream.bufferedReader().readText().trim()
                assertEquals(0,process.exitValue(),"$title $language decode failed: $decoded")
                assertTrue(decoded.contains("\"audioChannels\": \""),"Missing decoded audio: $decoded")
                assertTrue(decoded.contains("\"selectedAudioLanguage\": \"$language\""),"Wrong selected audio: $decoded")
                println("SALT-CURRENT $title S1E1 $language $decoded")
            }
        }
    }
}





