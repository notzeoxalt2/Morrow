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
import kotlinx.coroutines.async
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

        // 1. HiAnime (Starts immediately)
        val j1 = launch {
            val streams = runCatching {
                withTimeoutOrNull(8_000L) {
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

        // 2. Senshi (Fast native scraper - starts immediately)
        val j2 = launch {
            val streams = runCatching {
                withTimeoutOrNull(8_000L) {
                    SenshiScraper.getStreams(cleanTitle, epNum)
                } ?: emptyList()
            }.getOrElse { e ->
                log.w(e) { "Senshi error" }
                emptyList()
            }
            onGroupLoaded(
                AddonStreamGroup(
                    addonName = "Senshi",
                    addonId = "offline:senshi",
                    streams = streams,
                    isLoading = false,
                )
            )
        }

        // 3. AniDB (Starts immediately)
        val j3 = launch {
            val streams = runCatching {
                withTimeoutOrNull(8_000L) {
                    AniDbScraper.getStreams(cleanTitle, epNum)
                } ?: emptyList()
            }.getOrElse { e ->
                log.w(e) { "AniDB error" }
                emptyList()
            }
            onGroupLoaded(
                AddonStreamGroup(
                    addonName = "AniDB",
                    addonId = "offline:anidb",
                    streams = streams,
                    isLoading = false,
                )
            )
        }

        // 4. AnimeSalt (Starts immediately)
        val j4 = launch {
            val streams = runCatching {
                withTimeoutOrNull(8_000L) {
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

        // 5. AnimeLok (Awaits concurrent anilistId)
        val j5 = launch {
            val anilistId = anilistIdDeferred.await()
            val streams = runCatching {
                withTimeoutOrNull(8_000L) {
                    AnimeLokScraper.getStreams(cleanTitle, anilistId, epNum)
                } ?: emptyList()
            }.getOrElse { e ->
                log.w(e) { "AnimeLok error" }
                emptyList()
            }
            onGroupLoaded(
                AddonStreamGroup(
                    addonName = "AnimeLok",
                    addonId = "offline:animelok",
                    streams = streams,
                    isLoading = false,
                )
            )
        }

        // 6. Miruro (Awaits concurrent anilistId)
        val j6 = launch {
            val anilistId = anilistIdDeferred.await()
            val streams = runCatching {
                withTimeoutOrNull(8_000L) {
                    MiruroScraper.getStreams(cleanTitle, anilistId, epNum)
                } ?: emptyList()
            }.getOrElse { e ->
                log.w(e) { "Miruro error" }
                emptyList()
            }
            onGroupLoaded(
                AddonStreamGroup(
                    addonName = "Miruro",
                    addonId = "offline:miruro",
                    streams = streams,
                    isLoading = false,
                )
            )
        }

        // 7. KissKH (Starts immediately)
        val j7 = launch {
            val streams = runCatching {
                withTimeoutOrNull(8_000L) {
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

        // 8. AnimePahe (Starts immediately)
        val j8 = launch {
            val streams = runCatching {
                withTimeoutOrNull(8_000L) {
                    AnimePaheScraper.getStreams(cleanTitle, epNum)
                } ?: emptyList()
            }.getOrElse { e ->
                log.w(e) { "AnimePahe error" }
                emptyList()
            }
            onGroupLoaded(
                AddonStreamGroup(
                    addonName = "AnimePahe",
                    addonId = "offline:animepahe",
                    streams = streams,
                    isLoading = false,
                )
            )
        }

        // 9. AnimeDekho (Starts immediately)
        val j9 = launch {
            val streams = runCatching {
                withTimeoutOrNull(8_000L) {
                    AnimeDekhoScraper.getStreams(cleanTitle, epNum)
                } ?: emptyList()
            }.getOrElse { e ->
                log.w(e) { "AnimeDekho error" }
                emptyList()
            }
            onGroupLoaded(
                AddonStreamGroup(
                    addonName = "AnimeDekho",
                    addonId = "offline:animedekho",
                    streams = streams,
                    isLoading = false,
                )
            )
        }

        // 10. Anikage / ReAnime (Starts immediately)
        val j10 = launch {
            val streams = runCatching {
                withTimeoutOrNull(8_000L) {
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

        listOf(j1, j2, j3, j4, j5, j6, j7, j8, j9, j10).joinAll()
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
            return emptyList()
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
    // 6. AnimeSalt Scraper (Multi-Domain Fallback)
    // ==========================================
    private object AnimeSaltScraper {
        private val BASES = listOf("https://animesalt.cx", "https://saltanime.in", "https://animesalt.link")

        suspend fun getStreams(title: String, episodeNumber: Int): List<StreamItem> {
            val streams = mutableListOf<StreamItem>()
            for (base in BASES) {
                try {
                    val clean = title.lowercase().replace(" ", "-")
                    val searchUrl = "$base/watch/$clean-episode-$episodeNumber"
                    val response = httpGetTextWithHeaders(
                        searchUrl,
                        mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)", "Referer" to "$base/")
                    )
                    val regex = Regex("""(?:file|url)\s*:\s*["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""")
                    val matches = regex.findAll(response)
                    matches.forEachIndexed { idx, match ->
                        val url = match.groupValues[1]
                        streams.add(
                            StreamItem(
                                name = "AnimeSalt · Server ${idx + 1}",
                                title = "$title - Episode $episodeNumber",
                                description = "AnimeSalt Direct Stream",
                                url = url,
                                addonName = "AnimeSalt",
                                addonId = "offline:animesalt",
                                streamType = if (url.contains(".m3u8")) "m3u8" else "mp4",
                                behaviorHints = StreamBehaviorHints(
                                    proxyHeaders = StreamProxyHeaders(
                                        request = mapOf(
                                            "Referer" to "$base/",
                                            "Origin" to base,
                                            "User-Agent" to "Mozilla/5.0",
                                        )
                                    )
                                ),
                            )
                        )
                    }
                    if (streams.isNotEmpty()) break
                } catch (_: Throwable) {}
            }
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

                    val kwikMatches = Regex("""data-src=["'](https?://[^"']*kwik[^"']*)["']""").findAll(playPage)
                    kwikMatches.forEachIndexed { idx, match ->
                        val kwikUrl = match.groupValues[1]
                        streams.add(
                            StreamItem(
                                name = "AnimePahe · Server ${idx + 1}",
                                title = "$title - Episode $episodeNumber",
                                description = "AnimePahe Direct Stream",
                                url = kwikUrl,
                                addonName = "AnimePahe",
                                addonId = "offline:animepahe",
                                streamType = "m3u8",
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
    // 10. Anikage / ReAnime Scraper
    // ==========================================
    private object AnikageScraper {
        private val BASES = listOf("https://anikage.cc", "https://reanime.to")

        suspend fun getStreams(title: String, episodeNumber: Int): List<StreamItem> {
            val streams = mutableListOf<StreamItem>()
            for (base in BASES) {
                try {
                    val clean = title.lowercase().replace(" ", "-")
                    val watchUrl = "$base/watch/$clean-episode-$episodeNumber"
                    val response = httpGetTextWithHeaders(
                        watchUrl,
                        mapOf("User-Agent" to "Mozilla/5.0", "Referer" to "$base/")
                    )
                    val matches = Regex("""(?:file|url|source)\s*:\s*["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""").findAll(response)
                    matches.forEachIndexed { idx, match ->
                        val url = match.groupValues[1]
                        streams.add(
                            StreamItem(
                                name = "Anikage · Server ${idx + 1}",
                                title = "$title - Episode $episodeNumber",
                                description = "Anikage Direct Stream",
                                url = url,
                                addonName = "Anikage",
                                addonId = "offline:anikage",
                                streamType = if (url.contains(".m3u8")) "m3u8" else "mp4",
                                behaviorHints = StreamBehaviorHints(
                                    proxyHeaders = StreamProxyHeaders(
                                        request = mapOf("Referer" to "$base/", "Origin" to base)
                                    )
                                ),
                            )
                        )
                    }
                    if (streams.isNotEmpty()) break
                } catch (_: Throwable) {}
            }
            return streams
        }
    }
}
