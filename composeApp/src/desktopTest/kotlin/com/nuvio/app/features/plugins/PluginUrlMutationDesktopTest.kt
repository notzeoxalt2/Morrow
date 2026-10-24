package com.nuvio.app.features.plugins

import com.nuvio.app.features.plugins.runtime.PluginRuntime
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class PluginUrlMutationDesktopTest {
    @Test fun queryChangesReachProviderRequestUrls() = runBlocking {
        val results = PluginRuntime.executePlugin(
            code = """
                module.exports.getStreams = async function() {
                    var url = new URL('https://example.test/episodes?season=5#list');
                    url.searchParams.set('season', 1);
                    url.searchParams.append('post', 825);
                    var request = url.href;
                    url.searchParams.delete('season');
                    url.searchParams.delete('post');
                    return [{url: request, title: String(url) + '|' + url.search, provider: 'URL fixture'}];
                };
            """.trimIndent(),
            tmdbId = "20", mediaType = "anime", season = 1, episode = 1,
            scraperId = "url-query-mutation-test",
        )
        assertEquals("https://example.test/episodes?season=1&post=825#list", results.single().url)
        assertEquals("https://example.test/episodes#list|", results.single().title)
    }
}
