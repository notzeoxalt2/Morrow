/**
 * Standalone Bundled Scraper: LunarX
 * Source Site: https://lunarx.to/
 * API: anidap.lol / chad.anidap.lol
 */
if (typeof setTimeout === 'undefined') {
    globalThis.setTimeout = function(fn) { try { fn(); } catch(e) {} return 1; };
}
if (typeof clearTimeout === 'undefined') {
    globalThis.clearTimeout = function() {};
}

const TMDB_API_KEY = "439c478a771f35c05022f9feabcca01c";
const TMDB_BASE = "https://api.themoviedb.org/3";
const RESOLVER_BASE = "https://anidap.lol";
const STREAM_BASE = "https://chad.anidap.lol/rest/api";
const USER_AGENT =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0 Safari/537.36";

// Working server IDs confirmed from chad.anidap.lol
const DEFAULT_SERVERS = {
  subProviders: [
    { id: "zuna", default: true, tip: "Soft sub, Fast, High quality" },
    { id: "sora", default: false, tip: "Soft sub, Fast, High quality" }
  ],
  dubProviders: [
    { id: "sora", default: true, tip: "Soft sub, Fast, High quality" },
    { id: "zuna", default: false, tip: "Soft sub, Fast, High quality" }
  ]
};

const cache = new Map();
const pending = new Map();

function normalized(value) {
  return String(value || "")
    .normalize("NFKD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, " ")
    .trim();
}

function words(value) {
  return normalized(value).split(/\s+/).filter((word) => word.length > 1);
}

function titles(result) {
  const title = result && result.title;
  if (title && typeof title === "object") {
    return [title.english, title.romaji, title.native, title.userPreferred].filter(Boolean);
  }
  return [title, result && result.name].filter(Boolean);
}

async function jsonFetch(url, options = {}, timeout = 9000) {
  const headers = {
    Accept: "application/json, text/plain, */*",
    "User-Agent": USER_AGENT,
    ...(options.headers || {})
  };
  if (url.includes("anidap.lol")) {
    headers.Origin = RESOLVER_BASE;
    headers.Referer = `${RESOLVER_BASE}/`;
  }
  const response = await fetch(url, { ...options, headers });
  if (!response.ok) throw new Error(`HTTP ${response.status}: ${url}`);
  return await response.json();
}

async function retryJsonFetch(url, options = {}, timeout = 9000, attempts = 2) {
  let lastError;
  for (let attempt = 0; attempt < attempts; attempt += 1) {
    try {
      return await jsonFetch(url, options, timeout);
    } catch (error) {
      lastError = error;
    }
  }
  throw lastError;
}

async function cached(key, task, ttl = 300000) {
  const current = cache.get(key);
  if (current && current.expires > Date.now()) return current.value;
  if (pending.has(key)) return pending.get(key);
  const promise = Promise.resolve().then(task);
  pending.set(key, promise);
  try {
    const value = await promise;
    cache.set(key, { value, expires: Date.now() + ttl });
    return value;
  } finally {
    pending.delete(key);
  }
}

async function tmdbInfo(id, type, season) {
  const mediaType = type === "movie" ? "movie" : "tv";
  // Handle IMDb IDs
  if (String(id).startsWith("tt")) {
    const findData = await jsonFetch(
      `${TMDB_BASE}/find/${encodeURIComponent(id)}?api_key=${TMDB_API_KEY}&external_source=imdb_id`
    );
    const match = (findData.tv_results && findData.tv_results[0]) ||
                  (findData.movie_results && findData.movie_results[0]);
    if (!match) throw new Error(`TMDB find failed for ${id}`);
    const mainTitle = match.name || match.title || match.original_name || "";
    return { title: mainTitle, searchTitles: [mainTitle], year: (match.first_air_date || match.release_date || "").slice(0, 4) };
  }
  const details = await jsonFetch(
    `${TMDB_BASE}/${mediaType}/${encodeURIComponent(id)}?api_key=${TMDB_API_KEY}`
  );
  const mainTitle = details.title || details.name || details.original_title || details.original_name || "";
  const searchTitles = [mainTitle, details.original_title, details.original_name].filter(Boolean);
  let year = (details.release_date || details.first_air_date || "").slice(0, 4);
  if (mediaType === "tv" && Number(season) > 0) {
    try {
      const seasonData = await jsonFetch(
        `${TMDB_BASE}/tv/${encodeURIComponent(id)}/season/${encodeURIComponent(season)}?api_key=${TMDB_API_KEY}`
      );
      if (seasonData.name) searchTitles.unshift(seasonData.name);
      if (seasonData.air_date) year = seasonData.air_date.slice(0, 4);
      if (Number(season) > 1) {
        searchTitles.push(`${mainTitle} Season ${season}`, `${mainTitle} S${season}`);
      }
    } catch (_) {
      if (Number(season) > 1) searchTitles.push(`${mainTitle} Season ${season}`, `${mainTitle} S${season}`);
    }
  }
  return { title: mainTitle, searchTitles: [...new Set(searchTitles)], year };
}

function score(result, queries, year, mediaType) {
  const resultTitles = titles(result).map(normalized);
  let value = 0;
  for (const resultTitle of resultTitles) {
    for (const queryValue of queries) {
      const query = normalized(queryValue);
      if (!query) continue;
      if (resultTitle === query) value = Math.max(value, 1000);
      else if (resultTitle.includes(query) || query.includes(resultTitle)) value = Math.max(value, 360);
      value = Math.max(value, words(query).filter((word) => resultTitle.includes(word)).length * 18);
    }
  }
  const resultYear = Number(result && result.releaseDate);
  if (year && resultYear) {
    const difference = Math.abs(Number(year) - resultYear);
    if (difference === 0) value += 120;
    else if (difference === 1) value += 35;
    else if (difference > 3) value -= 40;
  }
  const wantedType = mediaType === "movie" ? "movie" : "tv";
  const resultType = String((result && (result.type || result.format)) || "").toLowerCase();
  if (resultType.includes(wantedType)) value += 20;
  return value;
}

async function resolveAnime(info, mediaType) {
  const queries = info.searchTitles.slice(0, 4);
  const responses = await Promise.allSettled(
    queries.map((query) =>
      cached(`search:${normalized(query)}`, () =>
        jsonFetch(`${RESOLVER_BASE}/api/anime/search?q=${encodeURIComponent(query)}`, {}, 5000)
      )
    )
  );
  const candidates = [];
  const seen = new Set();
  for (const response of responses) {
    if (response.status !== "fulfilled") continue;
    const results = response.value && response.value.results;
    if (!Array.isArray(results)) continue;
    for (const result of results) {
      const id = String((result && result.id) || "");
      if (!id || seen.has(id)) continue;
      seen.add(id);
      candidates.push({ result, score: score(result, queries, info.year, mediaType) });
    }
  }
  candidates.sort((left, right) => right.score - left.score);
  return candidates[0] && candidates[0].result;
}

function cleanTrack(url) {
  return String(url || "").replace(/^https:\/\/{3,}/, "https://").replace(/^http:\/\/{3,}/, "http://");
}

function streamType(source) {
  const type = String((source && (source.type || source.mimeType)) || "").toLowerCase();
  const url = String((source && source.url) || "").toLowerCase();
  if (type.includes("mpegurl") || url.includes(".m3u8")) return "hls";
  if (type.includes("mpd") || url.includes(".mpd")) return "mpd";
  return "mp4";
}

function quality(source) {
  const text = `${(source && (source.quality || source.resolution)) || ""} ${(source && source.url) || ""}`;
  const match = text.toLowerCase().match(/(2160|1440|1080|720|480|360)p?/);
  return match ? `${match[1]}p` : "Auto";
}

async function allServerStreams(animeId, episode, servers) {
  const subServers = (Array.isArray(servers && servers.subProviders) ? servers.subProviders : [])
    .map((s) => ({ ...s, language: "SUB", isDub: false }));
  const dubServers = (Array.isArray(servers && servers.dubProviders) ? servers.dubProviders : [])
    .map((s) => ({ ...s, language: "DUB", isDub: true }));

  const allServers = [...subServers, ...dubServers];
  const seenKeys = new Set();
  const unique = [];
  for (const server of allServers) {
    const key = `${server.language}:${server.id}`;
    if (!server.id || seenKeys.has(key)) continue;
    seenKeys.add(key);
    unique.push(server);
  }

  const results = await Promise.allSettled(
    unique.map(async (server) => {
      const isDubParam = server.isDub ? "&isDub=true" : "";
      const url = `${STREAM_BASE}/sources?id=${encodeURIComponent(animeId)}&epNum=${encodeURIComponent(episode)}&providerId=${encodeURIComponent(server.id)}${isDubParam}`;
      const data = await retryJsonFetch(url, {}, 5000, 1);
      return { server, data };
    })
  );

  const streams = [];
  const seenUrls = new Set();
  for (const result of results) {
    if (result.status !== "fulfilled") continue;
    const { server, data } = result.value;
    const sources = Array.isArray(data && data.sources) ? data.sources : [];
    for (const source of sources) {
      const url = String((source && source.url) || "");
      const key = `${server.language}:${url}`;
      if (!url || seenUrls.has(key)) continue;
      seenUrls.add(key);
      const q = quality(source);
      const isDub = server.language === "DUB";
      const serverName = String(server.id || "").toUpperCase();
      streams.push({
        name: `Server ${serverName} [${server.language}] â€¢ ${q}`,
        title: `âš¡ ${q} | ${isDub ? "ðŸ—£ï¸ English Dub" : "ðŸ’¬ Japanese Sub"} | ðŸ“º Server ${serverName}`,
        url,
        quality: q,
        language: isDub ? "en" : "ja",
        type: streamType(source),
        headers: data.headers || source.headers || {},
        subtitles: Array.isArray(data.tracks)
          ? data.tracks.map((track) => ({
              url: cleanTrack(track.url),
              language: track.lang || track.language || "en",
              name: track.label || track.name || track.lang || "English"
            })).filter((track) => track.url)
          : [],
        provider: "anidap-resolver"
      });
    }
  }
  return streams;
}

async function getStreams(tmdbId, mediaType = "tv", season = 1, episode = 1) {
  try {
    const safeSeason = Number(season) > 0 ? Number(season) : 1;
    const safeEpisode = Number(episode) > 0 ? Number(episode) : 1;
    const info = await cached(
      `tmdb:${mediaType}:${tmdbId}:${safeSeason}`,
      () => tmdbInfo(tmdbId, mediaType, safeSeason),
      600000
    );
    const match = await resolveAnime(info, mediaType);
    if (!match || !match.id) return [];

    const details = await cached(
      `detail:${match.id}`,
      () => jsonFetch(`${RESOLVER_BASE}/api/anime/${encodeURIComponent(match.id)}`, {}, 5000),
      300000
    );
    const animeId = details && details.data && details.data.id;
    if (!animeId) return [];

    let servers;
    try {
      servers = await cached(
        `servers:${animeId}:${safeEpisode}`,
        () => jsonFetch(`${STREAM_BASE}/servers?id=${encodeURIComponent(animeId)}&epNum=${safeEpisode}`, {}, 5000),
        300000
      );
    } catch (_) {
      servers = DEFAULT_SERVERS;
    }
    return await cached(
      `streams:${animeId}:${safeEpisode}`,
      () => allServerStreams(animeId, safeEpisode, servers),
      300000
    );
  } catch (error) {
    console.error(`[LunarX] ${error && error.message ? error.message : error}`);
    return [];
  }
}

function providerForSite(name, url) {
  return {
    async getStreams(...args) {
      const streams = await getStreams(...args);
      return streams.map((stream) => ({
        ...stream,
        name: `${name} | ${stream.name}`,
        title: `${stream.title} | ${name}`,
        sourceSite: url
      }));
    }
  };
}

module.exports = providerForSite("LunarX", "https://lunarx.to/");
globalThis.getStreams = getStreams;

