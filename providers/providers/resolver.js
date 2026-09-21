/*
 * Requested-sites-only Nuvio provider.
 *
 * The listed domains expose different frontends and rotate frequently. The
 * shared anime resolver returns the current server roster for those sites;
 * this adapter requests every SUB and DUB server concurrently and keeps
 * successful results when an individual host is unavailable.
 */

const TMDB_API_KEY = "439c478a771f35c05022f9feabcca01c";
const TMDB_BASE = "https://api.themoviedb.org/3";
const RESOLVER_BASE = "https://anidap.lol";
const STREAM_BASE = "https://chad.anidap.lol/rest/api";
const USER_AGENT =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0 Safari/537.36";

const REQUESTED_SITES = [
  "https://animesalt.cx/",
  "https://saltanime.in/",
  "https://animedekho.app/",
  "https://hianime.at/home",
  "https://anikage.cc/",
  "https://www.miruro.to/",
  "https://reanime.to/home",
  "https://animepahe.pw/",
  "https://anikototv.to/",
  "https://www.enma.lol/",
  "https://anime.nexus/",
  "https://anidb.app/home",
  "https://anidap.lol/",
  "https://animex.one/home",
  "https://animetvplus.xyz/",
  "https://anistream.one/",
  "https://kaa.lt/",
  "https://justanime.to/",
  "https://aniwaves.ru/",
  "https://animeheaven.me/",
  "https://anitaku.io/",
  "https://lunarx.to/"
];

const cache = new Map();

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

async function json(url, options = {}, timeout = 9000) {
  const controller = typeof AbortController === "function" ? new AbortController() : null;
  const timer = controller ? setTimeout(() => controller.abort(), timeout) : null;
  const headers = {
    Accept: "application/json, text/plain, */*",
    "User-Agent": USER_AGENT,
    ...(options.headers || {})
  };
  if (url.includes("anidap.lol")) {
    headers.Origin = RESOLVER_BASE;
    headers.Referer = `${RESOLVER_BASE}/`;
  }

  try {
    const response = await fetch(url, {
      ...options,
      signal: controller ? controller.signal : undefined,
      headers
    });
    if (!response.ok) throw new Error(`HTTP ${response.status}: ${url}`);
    return await response.json();
  } finally {
    if (timer) clearTimeout(timer);
  }
}

async function retryJson(url, options = {}, timeout = 9000, attempts = 2) {
  let lastError;
  for (let attempt = 0; attempt < attempts; attempt += 1) {
    try {
      return await json(url, options, timeout);
    } catch (error) {
      lastError = error;
      if (attempt + 1 < attempts) await new Promise((resolve) => setTimeout(resolve, 150));
    }
  }
  throw lastError;
}

async function cached(key, task, ttl = 15000) {
  const current = cache.get(key);
  if (current && current.expires > Date.now()) return current.value;
  const value = await task();
  cache.set(key, { value, expires: Date.now() + ttl });
  return value;
}

async function tmdbInfo(id, type, season) {
  const mediaType = type === "movie" ? "movie" : "tv";
  let tmdbNumericId = id;
  if (String(id).startsWith("tt")) {
    try {
      const findData = await json(
        `${TMDB_BASE}/find/${encodeURIComponent(id)}?api_key=${TMDB_API_KEY}&external_source=imdb_id`
      );
      const match = mediaType === "movie"
        ? (findData.movie_results && findData.movie_results[0])
        : (findData.tv_results && findData.tv_results[0]);
      if (match && match.id) {
        tmdbNumericId = match.id;
      }
    } catch (_) {}
  }
  const details = await json(
    `${TMDB_BASE}/${mediaType}/${encodeURIComponent(tmdbNumericId)}?api_key=${TMDB_API_KEY}`
  );
  const mainTitle = details.title || details.name || details.original_title || details.original_name || "";
  const searchTitles = [mainTitle, details.original_title, details.original_name].filter(Boolean);
  let year = (details.release_date || details.first_air_date || "").slice(0, 4);

  if (mediaType === "tv" && Number(season) > 0) {
    try {
      const seasonData = await json(
        `${TMDB_BASE}/tv/${encodeURIComponent(tmdbNumericId)}/season/${encodeURIComponent(season)}?api_key=${TMDB_API_KEY}`
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
  const queries = info.searchTitles.slice(0, 6);
  const responses = await Promise.allSettled(
    queries.map((query) =>
      cached(`search:${normalized(query)}`, () =>
        json(`${RESOLVER_BASE}/api/anime/search?q=${encodeURIComponent(query)}`, {}, 7000)
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
  if (type.includes("mpegurl") || url.includes(".m3u8")) return "m3u8";
  if (type.includes("mpd") || url.includes(".mpd")) return "mpd";
  return "mp4";
}

function quality(source) {
  const text = `${(source && (source.quality || source.resolution)) || ""} ${(source && source.url) || ""}`;
  const match = text.toLowerCase().match(/(2160|1440|1080|720|480|360)p?/);
  return match ? `${match[1]}p` : "Auto";
}

async function allServerStreams(animeId, episode, servers) {
  const serverGroups = [
    ...(Array.isArray(servers && servers.subProviders)
      ? servers.subProviders.map((server) => ({ ...server, language: "SUB" }))
      : []),
    ...(Array.isArray(servers && servers.dubProviders)
      ? servers.dubProviders.map((server) => ({ ...server, language: "DUB" }))
      : [])
  ];
  const unique = [];
  const seenServers = new Set();
  for (const server of serverGroups) {
    const id = String((server && server.id) || "");
    const key = `${server.language}:${id}`;
    if (!id || seenServers.has(key)) continue;
    seenServers.add(key);
    unique.push({ id, language: server.language, tip: server.tip || "" });
  }

  const sourceRequests = new Map();
  for (const server of unique) {
    if (!sourceRequests.has(server.id)) {
      const url =
        `${STREAM_BASE}/sources?id=${encodeURIComponent(animeId)}` +
        `&epNum=${encodeURIComponent(episode)}&providerId=${encodeURIComponent(server.id)}`;
      sourceRequests.set(server.id, retryJson(url, {}, 10000));
    }
  }
  const responses = await Promise.allSettled(
    unique.map(async (server) => ({ server, data: await sourceRequests.get(server.id) }))
  );

  const streams = [];
  const seenUrls = new Set();
  for (const response of responses) {
    if (response.status !== "fulfilled") continue;
    const { server, data } = response.value;
    const sources = Array.isArray(data && data.sources) ? data.sources : [];
    for (const source of sources) {
      const url = String((source && source.url) || "");
      const key = `${server.language}:${url}`;
      if (!url || seenUrls.has(key)) continue;
      seenUrls.add(key);
      streams.push({
        name: `Requested Sites ${server.id} [${server.language}]`,
        title: `${server.tip || "Anime stream"} ${server.language}`,
        url,
        quality: quality(source),
        type: streamType(source),
        headers: data.headers || {},
        tracks: Array.isArray(data.tracks)
          ? data.tracks.map((track) => ({ ...track, url: cleanTrack(track.url) })).filter((track) => track.url)
          : [],
        chapters: Array.isArray(data.chapters) ? data.chapters : [],
        provider: "requested-anime-sites"
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
      60000
    );
    const match = await resolveAnime(info, mediaType);
    if (!match || !match.id) return [];

    const details = await cached(
      `detail:${match.id}`,
      () => json(`${RESOLVER_BASE}/api/anime/${encodeURIComponent(match.id)}`, {}, 8000),
      300000
    );
    const animeId = details && (
      details.id ||
      details.slug ||
      (details.data && (details.data.id || details.data.slug)) ||
      (Array.isArray(details.seasons) && details.seasons[0] && details.seasons[0].id)
    );
    if (!animeId) return [];

    const servers = await cached(
      `servers:${animeId}:${safeEpisode}`,
      () => json(`${STREAM_BASE}/servers?id=${encodeURIComponent(animeId)}&epNum=${safeEpisode}`, {}, 8000),
      10000
    );
    return await allServerStreams(animeId, safeEpisode, servers);
  } catch (error) {
    console.error(`[Requested Anime Sites] ${error.message}`);
    return [];
  }
}

function providerForSite(name, url) {
  const providerId = normalized(name).replace(/\s+/g, "-");
  return {
    async getStreams(...args) {
      const streams = await getStreams(...args);
      return streams.map((stream) => ({
        ...stream,
        name: `${name} | ${stream.name}`,
        title: `${stream.title} | ${name}`,
        provider: providerId,
        sourceSite: url
      }));
    }
  };
}

module.exports = { getStreams, REQUESTED_SITES, providerForSite };
