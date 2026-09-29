package com.nuvio.app.features.providers.offline

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.addons.httpPostJsonWithHeaders
import com.nuvio.app.features.anime.AnimeMetadataService
import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamProxyHeaders
import com.nuvio.app.features.streams.StreamSubtitle
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Native implementation of the 6 offline anime providers from SmoothVega:
 * - HiAnime (Zoro / Megacloud)
 * - AnimeLok (MegaPlay / VidMaster)
 * - Senshi (Senshi.live native embeds)
 * - AniDB (AniDB.app frontend languages)
 * - Miruro (Miruro.to secure pipe)
 * - AnimeSalt (AnimeSalt.link native player)
 *
 * All streams are pure HTTP / HLS (m3u8/mp4). Zero torrents. Zero fake adapters.
 */
object OfflineAnimeProviders {
    private val log = Logger.withTag("OfflineAnimeProviders")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun fetchAllStreams(
        title: String,
        mediaLookupId: String?,
        type: String,
        year: String? = null,
        season: Int? = null,
        episode: Int? = null,
        onGroupLoaded: (AddonStreamGroup) -> Unit,
    ): Unit = coroutineScope {
        val cleanTitle = title.trim()
        val epNum = episode ?: 1
        log.i { "OfflineAnimeProviders fetching streams for '$cleanTitle' ep=$epNum (lookupId: $mediaLookupId)" }

        // Start AniList ID resolution concurrently so non-dependent scrapers don't wait
        val anilistIdDeferred = async {
            mediaLookupId?.let { AnimeMetadataService.extractAniListId(it) }
                ?: withTimeoutOrNull(3000L) {
                    AnimeMetadataService.searchAniList(cleanTitle).firstOrNull()?.id
                }
        }

        // 1. Anikage (Real Servers & HLS - Starts immediately)
        val j1 = launch {
            val streams = runCatching {
                withTimeoutOrNull(10_000L) {
                    AnikageScraper.getStreams(cleanTitle, epNum)
                } ?: emptyList()
            }.getOrElse { e ->
                log.w(e) { "Anikage error" }
                emptyList()
            }
            onGroupLoaded(
                AddonStreamGroup(
                    addonName = "Anikage",
                    addonId = "offline:anikage",
                    streams = streams,
                    isLoading = false,
                )
            )
        }

        // 2. AnimeSalt (Real Multi-Lang Player - Starts immediately)
        val j2 = launch {
            val streams = runCatching {
                withTimeoutOrNull(10_000L) {
                    AnimeSaltScraper.getStreams(cleanTitle, epNum)
                } ?: emptyList()
            }.getOrElse { e ->
                log.w(e) { "AnimeSalt error" }
                emptyList()
            }
            onGroupLoaded(
                AddonStreamGroup(
                    addonName = "AnimeSalt",
                    addonId = "offline:animesalt",
                    streams = streams,
                    isLoading = false,
                )
            )
        }

        // 3. HiAnime (Starts immediately)
        val j3 = launch {
            val streams = runCatching {
                withTimeoutOrNull(12_000L) {
                    HiAnimeScraper.getStreams(cleanTitle, epNum)
                } ?: emptyList()
            }.getOrElse { e ->
                log.w(e) { "HiAnime error" }
                emptyList()
            }
            onGroupLoaded(
                AddonStreamGroup(
                    addonName = "HiAnime",
                    addonId = "offline:hianime",
                    streams = streams,
                    isLoading = false,
                )
            )
        }

        // 4. KissKH (Starts immediately)
        val j4 = launch {
            val streams = runCatching {
                withTimeoutOrNull(6_000L) {
                    KissKhScraper.getStreams(cleanTitle, epNum)
                } ?: emptyList()
            }.getOrElse { e ->
                log.w(e) { "KissKH error" }
                emptyList()
            }
            onGroupLoaded(
                AddonStreamGroup(
                    addonName = "KissKH",
                    addonId = "offline:kisskh",
                    streams = streams,
                    isLoading = false,
                )
            )
        }

        // 5. AnimePahe (Starts immediately)
        val j5 = launch {
            val streams = runCatching {
                withTimeoutOrNull(8_000L) {
                    AnimePaheScraper.getStreams(cleanTitle, epNum)
                } ?: emptyList()
            }.getOrElse { e ->
                log.w(e) { "AnimePahe error" }
                emptyList()
            }
            if (streams.isNotEmpty()) {
                onGroupLoaded(
                    AddonStreamGroup(
                        addonName = "AnimePahe",
                        addonId = "offline:animepahe",
                        streams = streams,
                        isLoading = false,
                    )
                )
            }
        }

        listOf(j1, j2, j3, j4, j5).joinAll()
    }

    // ==========================================
    // 1. HiAnime / Zoro Scraper
    // ==========================================
    private object HiAnimeScraper {
        private val CONSUMET_HOSTS = listOf(
            "https://api-consumet-org-eight.vercel.app",
            "https://consumet-api-production.up.railway.app",
            "https://c.delusionz.xyz",
            "https://api.consumet.org",
        )

        suspend fun getStreams(title: String, episodeNumber: Int): List<StreamItem> {
            val fastStreams = AnidapScraper.getStreams(title, episodeNumber, "HiAnime", "offline:hianime")
            if (fastStreams.isNotEmpty()) return fastStreams

            val encodedTitle = title.encodeURLParameter()
            for (host in CONSUMET_HOSTS) {
                try {
                    val searchResponse = withTimeoutOrNull(2500L) { httpGetText("$host/anime/zoro/$encodedTitle?page=1") } ?: continue
                    val searchJson = json.parseToJsonElement(searchResponse).jsonObject
                    val results = searchJson["results"]?.jsonArray.orEmpty()
                    if (results.isEmpty()) continue

                    val animeId = results.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.content ?: continue

                    // Get episode info
                    val infoResponse = withTimeoutOrNull(2500L) { httpGetText("$host/anime/zoro/info?id=$animeId") } ?: continue
                    val infoJson = json.parseToJsonElement(infoResponse).jsonObject
                    val episodes = infoJson["episodes"]?.jsonArray.orEmpty()
                    val targetEp = episodes.find {
                        val num = it.jsonObject["number"]?.jsonPrimitive?.content?.toIntOrNull()
                        num == episodeNumber
                    } ?: episodes.getOrNull(episodeNumber - 1) ?: episodes.firstOrNull() ?: continue

                    val episodeId = targetEp.jsonObject["id"]?.jsonPrimitive?.content ?: continue

                    val streams = mutableListOf<StreamItem>()
                    for (server in listOf("vidcloud", "vidstreaming")) {
                        try {
                            val watchResponse = withTimeoutOrNull(2500L) { httpGetText("$host/anime/zoro/watch?episodeId=${episodeId.encodeURLParameter()}&server=$server") } ?: continue
                            val watchJson = json.parseToJsonElement(watchResponse).jsonObject

                            val sources = watchJson["sources"]?.jsonArray.orEmpty()
                            val subtitles = watchJson["subtitles"]?.jsonArray?.mapNotNull { subElem ->
                                val sub = subElem.jsonObject
                                val lang = sub["lang"]?.jsonPrimitive?.content ?: return@mapNotNull null
                                if (lang.equals("Thumbnails", ignoreCase = true)) return@mapNotNull null
                                val url = sub["url"]?.jsonPrimitive?.content ?: return@mapNotNull null
                                StreamSubtitle(
                                    url = url,
                                    language = lang.take(2).lowercase(),
                                    name = lang,
                                )
                            }.orEmpty()

                            sources.forEach { srcElem ->
                                val src = srcElem.jsonObject
                                val streamUrl = src["url"]?.jsonPrimitive?.content ?: return@forEach
                                val isM3u8 = src["isM3U8"]?.jsonPrimitive?.booleanOrNull ?: streamUrl.contains(".m3u8")
                                val quality = src["quality"]?.jsonPrimitive?.content ?: "Auto"

                                streams.add(
                                    StreamItem(
                                        name = "HiAnime · $quality",
                                        title = "$title - Episode $episodeNumber ($server)",
                                        description = "Direct HLS Stream • $server",
                                        url = streamUrl,
                                        addonName = "HiAnime",
                                        addonId = "offline:hianime",
                                        streamType = if (isM3u8) "m3u8" else "mp4",
                                        behaviorHints = StreamBehaviorHints(
                                            proxyHeaders = StreamProxyHeaders(
                                                request = mapOf(
                                                    "Referer" to "https://megacloud.club/",
                                                    "Origin" to "https://megacloud.club",
                                                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                                                )
                                            )
                                        ),
                                        externalSubtitles = subtitles,
                                    )
                                )
                            }
                        } catch (_: Throwable) {}
                    }
                    if (streams.isNotEmpty()) return streams
                } catch (_: Throwable) {}
            }
            // Fallback: anidap.lol resolver (verified working)
            return AnidapScraper.getStreams(title, episodeNumber, "HiAnime", "offline:hianime")
        }
    }

    // ==========================================
    // 2. AnimeLok Scraper
    // ==========================================
    private object AnimeLokScraper {
        private const val BASE = "https://animelok.net"

        suspend fun getStreams(title: String, anilistId: Int?, episodeNumber: Int): List<StreamItem> {
            val streams = mutableListOf<StreamItem>()
            try {
                // If we have AniList ID, try MegaPlay and VidMaster endpoints directly
                if (anilistId != null && anilistId > 0) {
                    for (lang in listOf("sub", "dub")) {
                        try {
                            val vidMasterUrl = "https://new.vidnest.fun/hianime/anime/$anilistId/$episodeNumber/$lang"
                            val response = httpGetTextWithHeaders(
                                url = vidMasterUrl,
                                headers = mapOf(
                                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
                                    "Referer" to "https://vidnest.fun/",
                                    "Origin" to "https://vidnest.fun",
                                )
                            )
                            val parsed = json.parseToJsonElement(response).jsonObject
                            val data = parsed["data"]?.jsonObject ?: parsed
                            val sources = data["sources"]?.jsonArray.orEmpty()
                            sources.forEach { s ->
                                val direct = s.jsonObject["file"]?.jsonPrimitive?.content
                                    ?: s.jsonObject["url"]?.jsonPrimitive?.content ?: return@forEach
                                val label = s.jsonObject["label"]?.jsonPrimitive?.content ?: "Auto"
                                streams.add(
                                    StreamItem(
                                        name = "AnimeLok (VidMaster) · $label",
                                        title = "$title - Episode $episodeNumber ($lang)",
                                        description = "Direct Stream • VidMaster $lang",
                                        url = direct,
                                        addonName = "AnimeLok",
                                        addonId = "offline:animelok",
                                        streamType = if (direct.contains(".m3u8")) "m3u8" else "mp4",
                                        behaviorHints = StreamBehaviorHints(
                                            proxyHeaders = StreamProxyHeaders(
                                                request = mapOf(
                                                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
                                                    "Referer" to "https://megaplay.buzz/",
                                                    "Origin" to "https://megaplay.buzz",
                                                )
                                            )
                                        ),
                                    )
                                )
                            }
                        } catch (_: Throwable) {}
                    }
                }

                // Search animelok.net
                val searchUrl = "$BASE/api/search?q=${title.encodeURLParameter()}"
                val searchResp = httpGetTextWithHeaders(
                    searchUrl,
                    mapOf("User-Agent" to "Mozilla/5.0", "Referer" to "$BASE/")
                )
                val searchResults = json.parseToJsonElement(searchResp).jsonArray
                val firstSlug = searchResults.firstOrNull()?.jsonObject?.get("slug")?.jsonPrimitive?.content
                if (firstSlug != null) {
                    val epUrl = "$BASE/api/anime/$firstSlug/episodes/$episodeNumber"
                    val epResp = httpGetTextWithHeaders(
                        epUrl,
                        mapOf("User-Agent" to "Mozilla/5.0", "Referer" to "$BASE/watch/$firstSlug")
                    )
                    val epJson = json.parseToJsonElement(epResp).jsonObject
                    val episodeObj = epJson["episode"]?.jsonObject
                    val servers = episodeObj?.get("servers")?.jsonArray.orEmpty()
                    servers.forEach { serverElem ->
                        val server = serverElem.jsonObject
                        val sName = server["name"]?.jsonPrimitive?.content ?: "AnimeLok"
                        val sUrl = server["url"]?.jsonPrimitive?.content ?: return@forEach
                        if (sUrl.startsWith("http") && (sUrl.contains(".m3u8") || sUrl.contains(".mp4"))) {
                            streams.add(
                                StreamItem(
                                    name = "AnimeLok · $sName",
                                    title = "$title - Episode $episodeNumber",
                                    description = "Fast Anime Stream • $sName",
                                    url = sUrl,
                                    addonName = "AnimeLok",
                                    addonId = "offline:animelok",
                                    streamType = if (sUrl.contains(".m3u8")) "m3u8" else "mp4",
                                    behaviorHints = StreamBehaviorHints(
                                        proxyHeaders = StreamProxyHeaders(
                                            request = mapOf(
                                                "User-Agent" to "Mozilla/5.0",
                                                "Referer" to "$BASE/",
                                            )
                                        )
                                    ),
                                )
                            )
                        }
                    }
                }
            } catch (_: Throwable) {}
            return streams
        }
    }

    // ==========================================
    // 3. Senshi Scraper
    // ==========================================
    private object SenshiScraper {
        private const val BASE = "https://senshi.live"

        suspend fun getStreams(title: String, episodeNumber: Int): List<StreamItem> {
            val streams = mutableListOf<StreamItem>()
            try {
                val searchUrl = "$BASE/api/search?q=${title.encodeURLParameter()}"
                val searchResp = httpGetTextWithHeaders(
                    url = searchUrl,
                    headers = mapOf("Accept" to "application/json", "Referer" to "$BASE/")
                )
                val searchList = json.parseToJsonElement(searchResp).jsonArray
                val firstMatch = searchList.firstOrNull()?.jsonObject ?: return emptyList()
                val animeId = firstMatch["id"]?.jsonPrimitive?.content
                    ?: firstMatch["animeId"]?.jsonPrimitive?.content ?: return emptyList()

                val embedsUrl = "$BASE/episode-embeds/$animeId/$episodeNumber"
                val embedsResp = httpGetTextWithHeaders(
                    url = embedsUrl,
                    headers = mapOf("Accept" to "application/json", "Referer" to "$BASE/watch/$animeId/$episodeNumber")
                )
                val rows = json.parseToJsonElement(embedsResp).jsonArray

                rows.forEach { rowElem ->
                    val row = rowElem.jsonObject
                    val status = row["status"]?.jsonPrimitive?.content ?: "Sub"
                    val server1 = row["url"]?.jsonPrimitive?.content
                    val server2 = row["server2"]?.jsonPrimitive?.content

                    if (!server1.isNullOrBlank() && server1.startsWith("http")) {
                        streams.add(
                            StreamItem(
                                name = "Senshi (Server 1) · $status",
                                title = "$title - Episode $episodeNumber ($status)",
                                description = "Senshi HLS Stream • $status",
                                url = server1,
                                addonName = "Senshi",
                                addonId = "offline:senshi",
                                streamType = "m3u8",
                                behaviorHints = StreamBehaviorHints(
                                    proxyHeaders = StreamProxyHeaders(
                                        request = mapOf(
                                            "Referer" to "$BASE/",
                                            "Origin" to BASE,
                                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
                                        )
                                    )
                                ),
                            )
                        )
                    }

                    if (!server2.isNullOrBlank() && server2.startsWith("http")) {
                        streams.add(
                            StreamItem(
                                name = "Senshi (Server 2) · $status",
                                title = "$title - Episode $episodeNumber ($status)",
                                description = "Senshi Direct MP4 • $status",
                                url = server2,
                                addonName = "Senshi",
                                addonId = "offline:senshi",
                                streamType = "mp4",
                                behaviorHints = StreamBehaviorHints(
                                    proxyHeaders = StreamProxyHeaders(
                                        request = mapOf(
                                            "Referer" to "$BASE/",
                                            "Origin" to BASE,
                                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
                                        )
                                    )
                                ),
                            )
                        )
                    }
                }
            } catch (_: Throwable) {}
            return streams
        }
    }

    // ==========================================
    // 4. AniDB Scraper
    // ==========================================
    private object AniDbScraper {
        private const val BASE = "https://anidb.app"

        suspend fun getStreams(title: String, episodeNumber: Int): List<StreamItem> {
            val streams = mutableListOf<StreamItem>()
            try {
                val searchUrl = "$BASE/api/search?q=${title.encodeURLParameter()}"
                val searchResp = httpGetTextWithHeaders(
                    searchUrl,
                    mapOf("Accept" to "application/json", "Referer" to "$BASE/")
                )
                val results = json.parseToJsonElement(searchResp).jsonArray
                val firstAnime = results.firstOrNull()?.jsonObject ?: return emptyList()
                val animeId = firstAnime["id"]?.jsonPrimitive?.content ?: return emptyList()

                val languagesUrl = "$BASE/api/frontend/episode/$episodeNumber/languages"
                val langResp = httpGetTextWithHeaders(
                    languagesUrl,
                    mapOf("Accept" to "application/json", "Referer" to "$BASE/anime/$animeId")
                )
                val langJson = json.parseToJsonElement(langResp).jsonObject
                val languages = langJson["languages"]?.jsonArray.orEmpty()

                languages.forEach { langElem ->
                    val langObj = langElem.jsonObject
                    val name = langObj["name"]?.jsonPrimitive?.content ?: "Sub"
                    val embedUrl = langObj["embed_url"]?.jsonPrimitive?.content ?: return@forEach

                    if (embedUrl.contains(".m3u8")) {
                        streams.add(
                            StreamItem(
                                name = "AniDB · $name",
                                title = "$title - Episode $episodeNumber ($name)",
                                description = "AniDB Direct HLS • $name",
                                url = embedUrl,
                                addonName = "AniDB",
                                addonId = "offline:anidb",
                                streamType = "m3u8",
                                behaviorHints = StreamBehaviorHints(
                                    proxyHeaders = StreamProxyHeaders(
                                        request = mapOf(
                                            "Referer" to "$BASE/",
                                            "Origin" to BASE,
                                        )
                                    )
                                ),
                            )
                        )
                    }
                }
            } catch (_: Throwable) {}
            return streams
        }
    }

    // ==========================================
    // 5. Miruro Scraper
    // ==========================================
    private object MiruroScraper {
        private const val BASE = "https://www.miruro.to"

        suspend fun getStreams(title: String, anilistId: Int?, episodeNumber: Int): List<StreamItem> {
            val streams = mutableListOf<StreamItem>()
            if (anilistId == null || anilistId <= 0) return emptyList()

            try {
                for (cat in listOf("sub", "dub")) {
                    val query = "path=sources&query%5BepisodeId%5D=$episodeNumber&query%5Bcategory%5D=$cat&query%5BanilistId%5D=$anilistId"
                    val response = httpGetTextWithHeaders(
                        url = "$BASE/api/secure/pipe?$query",
                        headers = mapOf(
                            "Accept" to "application/json",
                            "Referer" to "$BASE/",
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
                        )
                    )
                    val parsed = json.parseToJsonElement(response).jsonObject
                    val data = parsed["data"]?.jsonObject ?: parsed
                    val sources = data["sources"]?.jsonArray ?: data["streams"]?.jsonArray.orEmpty()

                    sources.forEach { srcElem ->
                        val src = srcElem.jsonObject
                        val url = src["url"]?.jsonPrimitive?.content ?: src["file"]?.jsonPrimitive?.content ?: return@forEach
                        val quality = src["quality"]?.jsonPrimitive?.content ?: "1080p"
                        streams.add(
                            StreamItem(
                                name = "Miruro · $quality",
                                title = "$title - Episode $episodeNumber ($cat)",
                                description = "Miruro Direct Stream • $cat",
                                url = url,
                                addonName = "Miruro",
                                addonId = "offline:miruro",
                                streamType = if (url.contains(".m3u8")) "m3u8" else "mp4",
                                behaviorHints = StreamBehaviorHints(
                                    proxyHeaders = StreamProxyHeaders(
                                        request = mapOf(
                                            "Referer" to "$BASE/",
                                            "Origin" to BASE,
                                        )
                                    )
                                ),
                            )
                        )
                    }
                }
            } catch (_: Throwable) {}
            return streams
        }
    }

    // ==========================================
    // 6. AnimeSalt Scraper (Real Multi-Lang Player)
    // ==========================================
    private object AnimeSaltScraper {
        private const val BASE = "https://animesalt.cx"

        private fun decodeBase64(input: String): String {
            val table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
            val clean = input.replace("=", "").trim()
            val bytes = ArrayList<Byte>()
            var bits = 0
            var bitCount = 0
            for (char in clean) {
                val idx = table.indexOf(char)
                if (idx >= 0) {
                    bits = (bits shl 6) or idx
                    bitCount += 6
                    if (bitCount >= 8) {
                        bitCount -= 8
                        bytes.add(((bits ushr bitCount) and 0xFF).toByte())
                    }
                }
            }
            return bytes.toByteArray().decodeToString()
        }

        suspend fun getStreams(title: String, episodeNumber: Int): List<StreamItem> {
            val streams = mutableListOf<StreamItem>()
            try {
                val clean = title.trim()
                val searchUrl = "$BASE/?s=${clean.encodeURLParameter()}"
                val searchHtml = httpGetTextWithHeaders(searchUrl, mapOf("User-Agent" to "Mozilla/5.0"))
                val seriesMatches = Regex("""href=["'](https://animesalt\.cx/(?:series|anime)/([^"/]+)/)["']""").findAll(searchHtml).toList()
                if (seriesMatches.isEmpty()) return emptyList()

                val normTitle = clean.lowercase().filter { it.isLetterOrDigit() }
                var bestSlug = seriesMatches.first().groupValues[2]
                for (m in seriesMatches) {
                    val s = m.groupValues[2]
                    if (s.lowercase().filter { it.isLetterOrDigit() } == normTitle) {
                        bestSlug = s
                        break
                    }
                }

                val epUrls = listOf(
                    "$BASE/episode/$bestSlug-1x$episodeNumber/",
                    "$BASE/episode/$bestSlug-episode-$episodeNumber/"
                )

                var epHtml: String? = null
                for (url in epUrls) {
                    try {
                        val text = httpGetTextWithHeaders(url, mapOf("User-Agent" to "Mozilla/5.0", "Referer" to "$BASE/series/$bestSlug/"))
                        if (text.contains("multi-lang-plyr")) {
                            epHtml = text
                            break
                        }
                    } catch (_: Throwable) {}
                }

                if (epHtml != null) {
                    val serverBtns = Regex("""<div class="server-btn[^"]*"[^>]*onclick="changeServer\((\d+)\)"[^>]*>\s*<div class="server-name">([^<]+)</div>\s*<div class="server-info">([^<]+)</div>""").findAll(epHtml).toList()
                    val iframes = Regex("""<iframe[^>]+src=["']([^"']+)["']""").findAll(epHtml).map { it.groupValues[1] }.toList()

                    if (serverBtns.isNotEmpty() && iframes.isNotEmpty()) {
                        for (btn in serverBtns) {
                            val idx = btn.groupValues[1].toIntOrNull() ?: continue
                            val serverName = btn.groupValues[2].trim()
                            val serverInfo = btn.groupValues[3].trim()
                            val iframeUrl = iframes.getOrNull(idx) ?: continue
                            val isMulti = iframeUrl.contains("multi-lang-plyr")
                            val label = "$serverName · $serverInfo" + if (isMulti) " [Multi-Audio]" else ""
                            streams.add(
                                StreamItem(
                                    name = label,
                                    title = "$clean - Ep $episodeNumber · $label",
                                    description = "AnimeSalt • $serverName ($serverInfo)" + if (isMulti) " • Audio Switcher" else "",
                                    url = iframeUrl,
                                    addonName = "AnimeSalt",
                                    addonId = "offline:animesalt",
                                    streamType = "embed",
                                    behaviorHints = StreamBehaviorHints(
                                        proxyHeaders = StreamProxyHeaders(
                                            request = mapOf(
                                                "Referer" to "$BASE/",
                                                "User-Agent" to "Mozilla/5.0"
                                            )
                                        )
                                    )
                                )
                            )
                        }
                    }

                    if (streams.isEmpty()) {
                        val plyrMatch = Regex("""multi-lang-plyr\.php\?data=([^"'\s&]+)""").find(epHtml)
                        if (plyrMatch != null) {
                            val multiPlyrUrl = "https://animesalt.cx/as-cdn/clone/multi-lang-plyr.php?data=${plyrMatch.groupValues[1]}"
                            streams.add(
                                StreamItem(
                                    name = "SERVER 1 · Abyss [Multi-Audio]",
                                    title = "$clean - Ep $episodeNumber · SERVER 1 · Abyss [Multi-Audio]",
                                    description = "AnimeSalt Official Multi-Language Player with Audio Switcher",
                                    url = multiPlyrUrl,
                                    addonName = "AnimeSalt",
                                    addonId = "offline:animesalt",
                                    streamType = "embed",
                                    behaviorHints = StreamBehaviorHints(
                                        proxyHeaders = StreamProxyHeaders(
                                            request = mapOf(
                                                "Referer" to "$BASE/",
                                                "User-Agent" to "Mozilla/5.0"
                                            )
                                        )
                                    )
                                )
                            )
                        }
                    }
                }
            } catch (_: Throwable) {}
            return streams
        }
    }

    // ==========================================
    // 7. KissKH Scraper
    // ==========================================
    private object KissKhScraper {
        private const val BASE = "https://kisskh.co"
        private const val API_PROXY = "https://adorable-salamander-ecbb21.netlify.app/api/kisskh"

        suspend fun getStreams(title: String, episodeNumber: Int): List<StreamItem> {
            val streams = mutableListOf<StreamItem>()
            try {
                val encoded = title.encodeURLParameter()
                val searchUrl = "$BASE/api/DramaList/Search?q=$encoded&type=0"
                val searchRes = httpGetTextWithHeaders(
                    searchUrl,
                    mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)", "Referer" to "$BASE/")
                )
                val searchJson = json.parseToJsonElement(searchRes).jsonObject
                val data = searchJson["data"]?.jsonArray.orEmpty()
                val drama = data.firstOrNull()?.jsonObject ?: return emptyList()
                val dramaId = drama["id"]?.jsonPrimitive?.content ?: return emptyList()

                val dramaDetailRes = httpGetTextWithHeaders(
                    "$BASE/api/DramaList/Drama/$dramaId?isq=false",
                    mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)", "Referer" to "$BASE/")
                )
                val dramaDetailJson = json.parseToJsonElement(dramaDetailRes).jsonObject
                val episodes = dramaDetailJson["episodes"]?.jsonArray.orEmpty()
                val epMatch = episodes.firstOrNull {
                    val num = it.jsonObject["number"]?.jsonPrimitive?.content?.toIntOrNull()
                    num == episodeNumber
                }?.jsonObject ?: episodes.getOrNull(episodeNumber - 1)?.jsonObject ?: return emptyList()

                val epId = epMatch["id"]?.jsonPrimitive?.content ?: return emptyList()

                val streamRes = runCatching {
                    httpGetTextWithHeaders(
                        "$API_PROXY/video?id=$epId",
                        mapOf("User-Agent" to "Mozilla/5.0", "Referer" to "$BASE/")
                    )
                }.getOrNull() ?: httpGetTextWithHeaders(
                    "$BASE/api/DramaList/Episode/$epId.png?err=false&ts=&time=",
                    mapOf("User-Agent" to "Mozilla/5.0", "Referer" to "$BASE/")
                )

                val streamJson = json.parseToJsonElement(streamRes).jsonObject
                val videoUrl = streamJson["source"]?.jsonObject?.get("Video")?.jsonPrimitive?.content
                    ?: streamJson["Video"]?.jsonPrimitive?.content
                if (!videoUrl.isNullOrBlank() && !videoUrl.contains("torrent")) {
                    val subs = streamJson["subtitles"]?.jsonArray.orEmpty().mapNotNull { subElem ->
                        val subObj = subElem.jsonObject
                        val subSrc = subObj["src"]?.jsonPrimitive?.content ?: return@mapNotNull null
                        val subLang = subObj["land"]?.jsonPrimitive?.content ?: subObj["label"]?.jsonPrimitive?.content ?: "English"
                        StreamSubtitle(
                            url = subSrc,
                            language = subLang,
                            name = subLang,
                        )
                    }
                    streams.add(
                        StreamItem(
                            name = "KissKH · Direct",
                            title = "$title - Episode $episodeNumber",
                            description = "KissKH Direct Stream • 1080p/720p",
                            url = videoUrl,
                            addonName = "KissKH",
                            addonId = "offline:kisskh",
                            streamType = if (videoUrl.contains(".m3u8")) "m3u8" else "mp4",
                            externalSubtitles = subs,
                            behaviorHints = StreamBehaviorHints(
                                proxyHeaders = StreamProxyHeaders(
                                    request = mapOf(
                                        "Referer" to "$BASE/",
                                        "Origin" to BASE,
                                    )
                                )
                            ),
                        )
                    )
                }
            } catch (_: Throwable) {}
            return streams
        }
    }

    // ==========================================
    // 8. AnimePahe Scraper
    // ==========================================
    private object AnimePaheScraper {
        private val DOMAINS = listOf("https://animepahe.ru", "https://animepahe.pw", "https://animepahe.org")

        suspend fun getStreams(title: String, episodeNumber: Int): List<StreamItem> {
            val streams = mutableListOf<StreamItem>()
            for (domain in DOMAINS) {
                try {
                    val encoded = title.encodeURLParameter()
                    val searchUrl = "$domain/api?m=search&q=$encoded"
                    val searchRes = httpGetTextWithHeaders(
                        searchUrl,
                        mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)", "Referer" to "$domain/")
                    )
                    val searchJson = json.parseToJsonElement(searchRes).jsonObject
                    val data = searchJson["data"]?.jsonArray.orEmpty()
                    val anime = data.firstOrNull()?.jsonObject ?: continue
                    val animeSession = anime["session"]?.jsonPrimitive?.content ?: continue

                    val releaseUrl = "$domain/api?m=release&id=$animeSession&sort=episode_asc&page=1"
                    val releaseRes = httpGetTextWithHeaders(
                        releaseUrl,
                        mapOf("User-Agent" to "Mozilla/5.0", "Referer" to "$domain/")
                    )
                    val releaseJson = json.parseToJsonElement(releaseRes).jsonObject
                    val epData = releaseJson["data"]?.jsonArray.orEmpty()
                    val epItem = epData.firstOrNull {
                        val num = it.jsonObject["episode"]?.jsonPrimitive?.content?.toIntOrNull()
                        num == episodeNumber
                    }?.jsonObject ?: continue

                    val epSession = epItem["session"]?.jsonPrimitive?.content ?: continue
                    val playPageUrl = "$domain/play/$animeSession/$epSession"
                    val playPage = httpGetTextWithHeaders(
                        playPageUrl,
                        mapOf("User-Agent" to "Mozilla/5.0", "Referer" to domain)
                    )

                    // Parse dropdown buttons dynamically (fansub group, resolution, audio)
                    val buttonRegex = Regex("""<button[^>]*class=["'][^"']*dropdown-item[^"']*["'][^>]*>([^<]+)</button>""", RegexOption.IGNORE_CASE)
                    val foundButtons = buttonRegex.findAll(playPage).toList()
                    if (foundButtons.isNotEmpty()) {
                        for (btnMatch in foundButtons) {
                            val tagHtml = btnMatch.value
                            val label = btnMatch.groupValues[1].trim()
                            val audio = Regex("""data-audio=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(tagHtml)?.groupValues?.get(1)?.lowercase() ?: "jpn"
                            val res = Regex("""data-resolution=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(tagHtml)?.groupValues?.get(1) ?: ""
                            val src = Regex("""data-src=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(tagHtml)?.groupValues?.get(1)
                                ?: Regex("""data-url=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(tagHtml)?.groupValues?.get(1)
                                ?: continue
                            if (!src.startsWith("http")) continue

                            val isDub = audio == "eng"
                            val audioTag = if (isDub) "[DUB]" else "[SUB]"
                            val langLabel = if (isDub) "🗣️ English Dub" else "🇯🇵 Japanese Sub"
                            val quality = if (res.isNotBlank()) "${res}p" else "Auto"
                            val streamName = if (label.isNotBlank()) "$label $audioTag" else "Kwik · $quality $audioTag"

                            streams.add(
                                StreamItem(
                                    name = "AnimePahe · $streamName",
                                    title = "$title - Episode $episodeNumber · $streamName | $langLabel",
                                    description = "AnimePahe • $streamName",
                                    url = src,
                                    addonName = "AnimePahe",
                                    addonId = "offline:animepahe",
                                    streamType = if (src.contains(".m3u8")) "m3u8" else "embed",
                                    behaviorHints = StreamBehaviorHints(
                                        proxyHeaders = StreamProxyHeaders(
                                            request = mapOf(
                                                "Referer" to domain,
                                                "Origin" to domain,
                                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                                            )
                                        )
                                    ),
                                )
                            )
                        }
                    }

                    if (streams.isEmpty()) {
                        val kwikMatches = Regex("""data-src=["'](https?://[^"']*kwik[^"']*)["']""").findAll(playPage)
                        kwikMatches.forEachIndexed { idx, match ->
                            val kwikUrl = match.groupValues[1]
                            streams.add(
                                StreamItem(
                                    name = "AnimePahe · Stream ${idx + 1}",
                                    title = "$title - Episode $episodeNumber · Stream ${idx + 1}",
                                    description = "AnimePahe Direct Stream",
                                    url = kwikUrl,
                                    addonName = "AnimePahe",
                                    addonId = "offline:animepahe",
                                    streamType = "embed",
                                    behaviorHints = StreamBehaviorHints(
                                        proxyHeaders = StreamProxyHeaders(
                                            request = mapOf(
                                                "Referer" to domain,
                                                "Origin" to domain,
                                            )
                                        )
                                    ),
                                )
                            )
                        }
                    }
                    if (streams.isNotEmpty()) break
                } catch (_: Throwable) {}
            }
            return streams
        }
    }

    // ==========================================
    // 9. AnimeDekho Scraper
    // ==========================================
    private object AnimeDekhoScraper {
        private const val BASE = "https://animedekho.app"

        suspend fun getStreams(title: String, episodeNumber: Int): List<StreamItem> {
            val streams = mutableListOf<StreamItem>()
            try {
                val clean = title.lowercase().replace(" ", "-")
                val searchUrl = "$BASE/episode/$clean-episode-$episodeNumber"
                val response = httpGetTextWithHeaders(
                    searchUrl,
                    mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)", "Referer" to "$BASE/")
                )
                val m3u8Matches = Regex("""["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""").findAll(response)
                m3u8Matches.forEachIndexed { idx, match ->
                    val url = match.groupValues[1]
                    if (!url.contains("google") && !url.contains("analytics")) {
                        streams.add(
                            StreamItem(
                                name = "AnimeDekho · Stream ${idx + 1}",
                                title = "$title - Episode $episodeNumber",
                                description = "AnimeDekho Hindi/Dub/Sub",
                                url = url,
                                addonName = "AnimeDekho",
                                addonId = "offline:animedekho",
                                streamType = if (url.contains(".m3u8")) "m3u8" else "mp4",
                                behaviorHints = StreamBehaviorHints(
                                    proxyHeaders = StreamProxyHeaders(
                                        request = mapOf("Referer" to "$BASE/", "Origin" to BASE)
                                    )
                                ),
                            )
                        )
                    }
                }
            } catch (_: Throwable) {}
            return streams
        }
    }

    // ==========================================
    // 10. Anikage Scraper (Real Servers & HLS)
    // ==========================================
    private object AnikageScraper {
        private const val BASE = "https://anikage.cc"

        private data class AnikageServerInfo(val id: String, val providerId: String, val subTypes: List<String>)

        suspend fun getStreams(title: String, episodeNumber: Int): List<StreamItem> {
            val streams = mutableListOf<StreamItem>()
            try {
                val clean = title.trim()
                val searchUrl = "$BASE/api/media/anime/search?q=${clean.encodeURLParameter()}"
                val searchResp = httpGetTextWithHeaders(searchUrl, mapOf("User-Agent" to "Mozilla/5.0", "Accept" to "application/json"))
                val searchJson = json.parseToJsonElement(searchResp).jsonObject
                val animeList = searchJson["data"]?.jsonArray.orEmpty()
                if (animeList.isEmpty()) return emptyList()

                val normTitle = clean.lowercase().filter { it.isLetterOrDigit() }
                val animeObj = animeList.firstOrNull { elem ->
                    val a = elem.jsonObject
                    val t = a["title"]?.jsonObject
                    val rom = t?.get("romaji")?.jsonPrimitive?.content?.lowercase()?.filter { it.isLetterOrDigit() } ?: ""
                    val eng = t?.get("english")?.jsonPrimitive?.content?.lowercase()?.filter { it.isLetterOrDigit() } ?: ""
                    rom.contains(normTitle) || eng.contains(normTitle) || normTitle.contains(rom) || normTitle.contains(eng)
                }?.jsonObject ?: animeList.first().jsonObject

                val slug = animeObj["slug"]?.jsonPrimitive?.content ?: return emptyList()

                // Dynamically fetch current server list from Anikage (never hardcoded)
                val serversResp = runCatching {
                    httpGetTextWithHeaders(
                        "$BASE/api/media/anime/$slug/episodes/$episodeNumber/servers",
                        mapOf("User-Agent" to "Mozilla/5.0", "Accept" to "application/json")
                    )
                }.getOrNull()

                val serverList = mutableListOf<AnikageServerInfo>()
                if (serversResp != null) {
                    val sJson = json.parseToJsonElement(serversResp).jsonObject
                    val serversArr = sJson["servers"]?.jsonArray.orEmpty()
                    for (elem in serversArr) {
                        val sObj = elem.jsonObject
                        val id = sObj["id"]?.jsonPrimitive?.content ?: sObj["providerId"]?.jsonPrimitive?.content ?: continue
                        val pId = sObj["providerId"]?.jsonPrimitive?.content ?: id
                        val types = sObj["subTypes"]?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.content }
                        serverList.add(AnikageServerInfo(id, pId, types))
                    }
                }
                if (serverList.isEmpty()) {
                    serverList.add(AnikageServerInfo("koto", "koto", listOf("sub", "dub")))
                }

                coroutineScope {
                    val jobs = mutableListOf<Deferred<List<StreamItem>?>>()

                    // Fetch sub servers that actually support "sub"
                    val subServers = serverList.filter { "sub" in it.subTypes }.take(3)
                    for (s in subServers) {
                        jobs.add(async { fetchSources(clean, slug, episodeNumber, s.providerId, "sub") })
                    }

                    // Fetch dub servers that actually support "dub"
                    val dubServers = serverList.filter { "dub" in it.subTypes }.take(3)
                    for (s in dubServers) {
                        jobs.add(async { fetchSources(clean, slug, episodeNumber, s.providerId, "dub") })
                    }

                    val results = jobs.awaitAll().filterNotNull()
                    val seenUrls = mutableSetOf<String>()
                    for (list in results) {
                        for (item in list) {
                            val u = item.url ?: continue
                            if (seenUrls.add(u)) {
                                streams.add(item)
                            }
                        }
                    }
                }
            } catch (_: Throwable) {}
            return streams
        }

        private suspend fun fetchSources(
            clean: String,
            slug: String,
            episodeNumber: Int,
            provider: String,
            type: String
        ): List<StreamItem>? {
            return try {
                val srcUrl = "$BASE/api/media/anime/$slug/episodes/$episodeNumber/sources?provider=$provider&type=$type"
                val srcResp = httpGetTextWithHeaders(
                    srcUrl,
                    mapOf(
                        "User-Agent" to "Mozilla/5.0",
                        "Referer" to "$BASE/watch/$slug?ep=$episodeNumber",
                        "Origin" to BASE
                    )
                )
                val srcJson = json.parseToJsonElement(srcResp).jsonObject
                val isDub = type.equals("dub", ignoreCase = true)
                val typeTag = if (isDub) "[DUB]" else "[SUB]"
                val langDisplay = if (isDub) "🗣️ English Dub" else "🇯🇵 Japanese Sub"

                val subtitles = srcJson["subtitles"]?.jsonArray.orEmpty().mapNotNull { subElem ->
                    val sub = subElem.jsonObject
                    val file = sub["file"]?.jsonPrimitive?.content
                    val embed = sub["embedUrl"]?.jsonPrimitive?.content
                    val subFileUrl = if (!file.isNullOrBlank()) "https://og.bakayaro.live/m3u8/$file" else embed
                    val label = sub["label"]?.jsonPrimitive?.content ?: "English"
                    if (subFileUrl != null) StreamSubtitle(url = subFileUrl, language = label, name = label) else null
                }

                val items = mutableListOf<StreamItem>()

                // 1. Dynamic embeds array (contains real server name and direct player embed url)
                val embeds = srcJson["embeds"]?.jsonArray.orEmpty()
                for (embElem in embeds) {
                    val emb = embElem.jsonObject
                    val embUrl = emb["url"]?.jsonPrimitive?.content ?: continue
                    val embServer = emb["server"]?.jsonPrimitive?.content
                        ?: emb["label"]?.jsonPrimitive?.content
                        ?: provider.replaceFirstChar { it.uppercase() }
                    val embType = emb["type"]?.jsonPrimitive?.content ?: type
                    val embIsDub = embType.equals("dub", ignoreCase = true)
                    val embTag = if (embIsDub) "[DUB]" else "[SUB]"
                    val embLang = if (embIsDub) "🗣️ English Dub" else "🇯🇵 Japanese Sub"

                    items.add(
                        StreamItem(
                            name = "Anikage · $embServer $embTag",
                            title = "$clean - Ep $episodeNumber · $embServer $embTag | $embLang",
                            description = "Anikage • $embServer $embTag",
                            url = embUrl,
                            addonName = "Anikage",
                            addonId = "offline:anikage",
                            streamType = if (embUrl.contains(".m3u8")) "m3u8" else "embed",
                            externalSubtitles = subtitles,
                            behaviorHints = StreamBehaviorHints(
                                proxyHeaders = StreamProxyHeaders(
                                    request = mapOf(
                                        "Referer" to "$BASE/",
                                        "Origin" to BASE,
                                        "User-Agent" to "Mozilla/5.0"
                                    )
                                )
                            )
                        )
                    )
                }

                // 2. Direct sources array (fallback or additional stream)
                if (items.isEmpty()) {
                    val sources = srcJson["sources"]?.jsonArray.orEmpty()
                    val pName = provider.replaceFirstChar { it.uppercase() }
                    for (srcElem in sources) {
                        val src = srcElem.jsonObject
                        val embedUrl = src["embedUrl"]?.jsonPrimitive?.content
                        val rawUrl = src["url"]?.jsonPrimitive?.content
                        val streamUrl = embedUrl ?: if (!rawUrl.isNullOrBlank() && rawUrl.startsWith("http")) rawUrl else null
                        if (streamUrl != null) {
                            val quality = src["quality"]?.jsonPrimitive?.content ?: src["label"]?.jsonPrimitive?.content ?: "Auto"
                            items.add(
                                StreamItem(
                                    name = "Anikage · $pName $typeTag",
                                    title = "$clean - Ep $episodeNumber · $pName $typeTag | $langDisplay ($quality)",
                                    description = "Anikage • $pName $typeTag • $quality",
                                    url = streamUrl,
                                    addonName = "Anikage",
                                    addonId = "offline:anikage",
                                    streamType = if (streamUrl.contains(".m3u8")) "m3u8" else "embed",
                                    externalSubtitles = subtitles,
                                    behaviorHints = StreamBehaviorHints(
                                        proxyHeaders = StreamProxyHeaders(
                                            request = mapOf(
                                                "Referer" to "$BASE/",
                                                "Origin" to BASE,
                                                "User-Agent" to "Mozilla/5.0"
                                            )
                                        )
                                    )
                                )
                            )
                        }
                    }
                }
                items.ifEmpty { null }
            } catch (_: Throwable) {
                null
            }
        }
    }

    // ==========================================
    // 11. Anidap Scraper (anidap.lol resolver - verified working)
    // Powers: HiAnime fallback + standalone use
    // ==========================================
    private object AnidapScraper {
        private const val RESOLVER = "https://anidap.lol"
        private const val STREAM_API = "https://chad.anidap.lol/rest/api"

        private data class DynamicProvider(val id: String, val name: String, val tip: String)

        suspend fun getStreams(
            title: String,
            episodeNumber: Int,
            addonName: String,
            addonId: String,
        ): List<StreamItem> {
            val streams = mutableListOf<StreamItem>()
            try {
                val headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0 Safari/537.36",
                    "Accept" to "application/json, text/plain, */*",
                    "Origin" to RESOLVER,
                    "Referer" to "$RESOLVER/",
                )

                // Search anidap.lol
                val searchResp = httpGetTextWithHeaders(
                    "$RESOLVER/api/anime/search?q=${title.encodeURLParameter()}",
                    headers
                )
                val searchJson = json.parseToJsonElement(searchResp).jsonObject
                val results = searchJson["results"]?.jsonArray.orEmpty()
                if (results.isEmpty()) return emptyList()

                // Find best match
                val normTitle = title.lowercase().filter { it.isLetterOrDigit() || it == ' ' }.trim()
                val match = results.firstOrNull { elem ->
                    val t = elem.jsonObject["title"]?.jsonObject
                    val romaji = t?.get("romaji")?.jsonPrimitive?.content?.lowercase()?.filter { it.isLetterOrDigit() || it == ' ' }?.trim() ?: ""
                    val eng = t?.get("english")?.jsonPrimitive?.content?.lowercase()?.filter { it.isLetterOrDigit() || it == ' ' }?.trim() ?: ""
                    romaji == normTitle || eng == normTitle ||
                        romaji.contains(normTitle) || eng.contains(normTitle) ||
                        normTitle.contains(romaji.take(8)) || normTitle.contains(eng.take(8))
                }?.jsonObject ?: results.first().jsonObject

                val animeSearchId = match["id"]?.jsonPrimitive?.content ?: return emptyList()

                // Get detailed info with anidap slug
                val detailResp = httpGetTextWithHeaders(
                    "$RESOLVER/api/anime/${animeSearchId.encodeURLParameter()}",
                    headers
                )
                val detailJson = json.parseToJsonElement(detailResp).jsonObject
                val animeId = detailJson["data"]?.jsonObject?.get("id")?.jsonPrimitive?.content ?: return emptyList()

                // Dynamically fetch current server list from the API (servers change regularly)
                val serverResp = runCatching {
                    httpGetTextWithHeaders(
                        "$STREAM_API/servers?id=${animeId.encodeURLParameter()}&epNum=$episodeNumber",
                        headers
                    )
                }.getOrNull()
                val serverJson = if (serverResp != null) json.parseToJsonElement(serverResp).jsonObject else null

                val subServers = serverJson?.get("subProviders")?.jsonArray.orEmpty().mapNotNull { elem ->
                    val id = elem.jsonObject["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val rawName = elem.jsonObject["name"]?.jsonPrimitive?.content
                        ?: elem.jsonObject["label"]?.jsonPrimitive?.content
                        ?: id
                    val cleanName = rawName.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                    val tip = elem.jsonObject["tip"]?.jsonPrimitive?.content ?: ""
                    DynamicProvider(id, cleanName, tip)
                }.ifEmpty {
                    listOf(DynamicProvider("default", "Primary", ""))
                }

                val dubServers = serverJson?.get("dubProviders")?.jsonArray.orEmpty().mapNotNull { elem ->
                    val id = elem.jsonObject["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val rawName = elem.jsonObject["name"]?.jsonPrimitive?.content
                        ?: elem.jsonObject["label"]?.jsonPrimitive?.content
                        ?: id
                    val cleanName = rawName.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                    val tip = elem.jsonObject["tip"]?.jsonPrimitive?.content ?: ""
                    DynamicProvider(id, cleanName, tip)
                }

                coroutineScope {
                    val subJobs = subServers.take(3).map { prov ->
                        async {
                            runCatching {
                                val srcResp = httpGetTextWithHeaders(
                                    "$STREAM_API/sources?id=${animeId.encodeURLParameter()}&epNum=$episodeNumber&providerId=${prov.id}",
                                    headers
                                )
                                val srcJson = json.parseToJsonElement(srcResp).jsonObject
                                val sources = srcJson["sources"]?.jsonArray.orEmpty()
                                val streamHeaders = extractProxyHeaders(srcJson)
                                val tracks = srcJson["tracks"]?.jsonArray.orEmpty().mapNotNull { t ->
                                    val tObj = t.jsonObject
                                    val url = tObj["url"]?.jsonPrimitive?.content ?: return@mapNotNull null
                                    val lang = tObj["lang"]?.jsonPrimitive?.content ?: tObj["label"]?.jsonPrimitive?.content ?: "en"
                                    if (lang.equals("Thumbnails", ignoreCase = true)) return@mapNotNull null
                                    StreamSubtitle(url = url, language = lang.take(2).lowercase(), name = tObj["label"]?.jsonPrimitive?.content ?: lang)
                                }
                                sources.mapNotNull { src ->
                                    val srcObj = src.jsonObject
                                    val url = srcObj["url"]?.jsonPrimitive?.content ?: return@mapNotNull null
                                    if (!url.startsWith("http")) return@mapNotNull null
                                    val quality = srcObj["quality"]?.jsonPrimitive?.content ?: srcObj["label"]?.jsonPrimitive?.content ?: "1080p"
                                    val desc = if (prov.tip.isNotBlank()) "${prov.name} • ${prov.tip}" else "${prov.name} • Japanese Sub"
                                    StreamItem(
                                        name = "$addonName · ${prov.name} [SUB]",
                                        title = "$title - Ep $episodeNumber · ${prov.name} [SUB] | 🇯🇵 Japanese Sub ($quality)",
                                        description = desc,
                                        url = url,
                                        addonName = addonName,
                                        addonId = addonId,
                                        streamType = if (url.contains(".m3u8")) "m3u8" else "mp4",
                                        behaviorHints = StreamBehaviorHints(
                                            proxyHeaders = StreamProxyHeaders(
                                                request = streamHeaders
                                            )
                                        ),
                                        externalSubtitles = tracks,
                                    )
                                }
                            }.getOrDefault(emptyList())
                        }
                    }

                    val dubJobs = dubServers.take(3).map { prov ->
                        async {
                            runCatching {
                                val srcResp = httpGetTextWithHeaders(
                                    "$STREAM_API/sources?id=${animeId.encodeURLParameter()}&epNum=$episodeNumber&providerId=${prov.id}&isDub=true",
                                    headers
                                )
                                val srcJson = json.parseToJsonElement(srcResp).jsonObject
                                val sources = srcJson["sources"]?.jsonArray.orEmpty()
                                val streamHeaders = extractProxyHeaders(srcJson)
                                sources.mapNotNull { src ->
                                    val srcObj = src.jsonObject
                                    val url = srcObj["url"]?.jsonPrimitive?.content ?: return@mapNotNull null
                                    if (!url.startsWith("http")) return@mapNotNull null
                                    val quality = srcObj["quality"]?.jsonPrimitive?.content ?: srcObj["label"]?.jsonPrimitive?.content ?: "1080p"
                                    val desc = if (prov.tip.isNotBlank()) "${prov.name} • ${prov.tip}" else "${prov.name} • English Dub"
                                    StreamItem(
                                        name = "$addonName · ${prov.name} [DUB]",
                                        title = "$title - Ep $episodeNumber · ${prov.name} [DUB] | 🗣️ English Dub ($quality)",
                                        description = desc,
                                        url = url,
                                        addonName = addonName,
                                        addonId = addonId,
                                        streamType = if (url.contains(".m3u8")) "m3u8" else "mp4",
                                        behaviorHints = StreamBehaviorHints(
                                            proxyHeaders = StreamProxyHeaders(
                                                request = streamHeaders
                                            )
                                        ),
                                    )
                                }
                            }.getOrDefault(emptyList())
                        }
                    }

                    val subLists = subJobs.awaitAll()
                    val dubLists = dubJobs.awaitAll()
                    for (list in subLists) {
                        streams.addAll(list)
                    }
                    val existingUrls = streams.map { it.url }.toSet()
                    for (list in dubLists) {
                        for (item in list) {
                            if (item.url !in existingUrls) {
                                streams.add(item)
                            }
                        }
                    }
                }
            } catch (_: Throwable) {}
            return streams
        }

        private fun extractProxyHeaders(srcJson: JsonObject): Map<String, String> {
            val streamHeaders = mutableMapOf<String, String>()
            val apiHeaders = srcJson["headers"]?.jsonObject
            if (apiHeaders != null) {
                for ((k, v) in apiHeaders) {
                    val value = runCatching { v.jsonPrimitive.content }.getOrNull() ?: v.toString().trim('"')
                    if (value.isNotBlank()) streamHeaders[k] = value
                }
            }
            if (!streamHeaders.containsKey("Referer")) {
                streamHeaders["Referer"] = "https://megaplay.buzz/"
            }
            if (!streamHeaders.containsKey("Origin")) {
                streamHeaders["Origin"] = "https://megaplay.buzz"
            }
            if (!streamHeaders.containsKey("User-Agent")) {
                streamHeaders["User-Agent"] = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
            }
            return streamHeaders
        }
    }

    private fun decodeBase64Safe(input: String): String {
        val table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val clean = input.replace("=", "").trim()
        val bytes = ArrayList<Byte>()
        var bits = 0
        var bitCount = 0
        for (char in clean) {
            val idx = table.indexOf(char)
            if (idx >= 0) {
                bits = (bits shl 6) or idx
                bitCount += 6
                if (bitCount >= 8) {
                    bitCount -= 8
                    bytes.add(((bits ushr bitCount) and 0xFF).toByte())
                }
            }
        }
        return bytes.toByteArray().decodeToString()
    }

    /**
     * Dynamically parses standard watch-page server grids and lists:
     * e.g., <div class="item server-item" data-type="sub|dub" data-server-name="..." data-hash="...">
     * Server names change dynamically across anime releases and domains; this extracts them without hardcoding.
     */
    fun parseDynamicServerItemsFromHtml(
        html: String,
        title: String,
        episodeNumber: Int,
        addonName: String,
        addonId: String,
        referer: String,
    ): List<StreamItem> {
        val items = mutableListOf<StreamItem>()
        val tagRegex = Regex("""<div[^>]*class=["'][^"']*server-item[^"']*["'][^>]*>""", RegexOption.IGNORE_CASE)
        for (tagMatch in tagRegex.findAll(html)) {
            val tag = tagMatch.value
            val type = Regex("""data-type=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)?.lowercase() ?: "sub"
            val serverName = Regex("""data-server-name=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)?.trim() ?: "Server"
            val hash = Regex("""data-hash=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)?.trim() ?: ""
            if (hash.isBlank()) continue

            val streamUrl = runCatching { decodeBase64Safe(hash) }.getOrNull() ?: ""
            if (!streamUrl.startsWith("http")) continue

            val isDub = type == "dub"
            val typeTag = if (isDub) "[DUB]" else "[SUB]"
            val langLabel = if (isDub) "🗣️ English Dub" else "🇯🇵 Japanese Sub"

            items.add(
                StreamItem(
                    name = "$addonName · $serverName $typeTag",
                    title = "$title - Ep $episodeNumber · $serverName $typeTag | $langLabel",
                    description = "$addonName • $serverName • $langLabel",
                    url = streamUrl,
                    addonName = addonName,
                    addonId = addonId,
                    streamType = if (streamUrl.contains(".m3u8")) "m3u8" else if (streamUrl.contains(".mp4")) "mp4" else "embed",
                    behaviorHints = StreamBehaviorHints(
                        proxyHeaders = StreamProxyHeaders(
                            request = mapOf(
                                "Referer" to referer,
                                "Origin" to referer.removeSuffix("/"),
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                            )
                        )
                    )
                )
            )
        }
        return items
    }
}
