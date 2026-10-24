/**
 * Morrow Standalone Anime Scraper: Anikage
 * Source Site: https://anikage.cc/
 * Real Server Integration with Native HLS Playback & Multi-Audio (Sub/Dub)
 */

const TMDB_API_KEY = "439c478a771f35c05022f9feabcca01c";
const USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";

async function getTitleFromTmdb(id, mediaType) {
  try {
    const isImdb = String(id).startsWith("tt");
    const url = isImdb
      ? `https://api.themoviedb.org/3/find/${id}?api_key=${TMDB_API_KEY}&external_source=imdb_id`
      : `https://api.themoviedb.org/3/${mediaType === "movie" ? "movie" : "tv"}/${id}?api_key=${TMDB_API_KEY}`;
    const res = await fetch(url);
    if (!res.ok) return null;
    const json = await res.json();
    if (isImdb) {
      const match = (json.tv_results && json.tv_results[0]) || (json.movie_results && json.movie_results[0]);
      return match ? (match.name || match.title || match.original_name) : null;
    }
    return json.name || json.title || json.original_name || json.original_title || null;
  } catch {
    return null;
  }
}

async function getStreams(tmdbId, mediaType = "tv", season = 1, episode = 1) {
  try {
    const context = globalThis.MORROW_MEDIA_CONTEXT || {};
    const safeEpisode = Math.max(1, parseInt(context.animeEpisode || episode, 10) || 1);
    if (!context.anilistId && Number(season) > 1) return [];
    let queryTitle = context.animeTitle || null;

    if (!queryTitle && (String(tmdbId).startsWith("tt") || /^\d+$/.test(String(tmdbId)))) {
      queryTitle = await getTitleFromTmdb(tmdbId, mediaType);
    } else if (!queryTitle) {
      queryTitle = String(tmdbId);
    }

    if (!queryTitle) return [];
    const cleanTitle = String(context.animeTitle || queryTitle).trim();

    const searchRes = await fetch(`https://anikage.cc/api/media/anime/search?q=${encodeURIComponent(cleanTitle)}`, {
      headers: {
        'User-Agent': USER_AGENT,
        'Accept': 'application/json'
      }
    });
    if (!searchRes.ok) return [];
    const searchJson = await searchRes.json();
    const animeList = (searchJson.data || []).filter(a =>
      mediaType === 'movie' ? a.format === 'MOVIE' : a.format !== 'MOVIE');
    if (!animeList.length) return [];

    const normTitle = cleanTitle.toLowerCase().replace(/[^a-z0-9]/g, '');
    const matches = context.anilistId
      ? animeList.filter(a => Number(a.anilistId) === Number(context.anilistId))
      : animeList.filter(a => [a.title?.romaji, a.title?.english, a.title?.native]
          .some(title => String(title || '').toLowerCase().replace(/[^a-z0-9]/g, '') === normTitle));
    if (matches.length !== 1) return [];
    const anime = matches[0];

    const slug = anime.slug;
    if (!slug) return [];

    const serversRes = await fetch(`https://anikage.cc/api/media/anime/${slug}/episodes/${safeEpisode}/servers`, {
      headers: {
        'User-Agent': USER_AGENT,
        'Accept': 'application/json'
      }
    });
    if (!serversRes.ok) return [];
    const serversJson = await serversRes.json();
    const serverList = serversJson.servers || [];

    const targetServerIds = serverList.map(item => item.providerId || item.id);
    const fetchTasks = [];
    let cooldownSeconds = 0;

    for (const s of targetServerIds) {
      const sInfo = serverList.find(item => (item.providerId || item.id) === s);
      const subTypes = sInfo?.subTypes || [];

      for (const t of subTypes) {
        fetchTasks.push(async () => {
          if (cooldownSeconds) return [];
          try {
            const srcRes = await morrowMediaFetch(`https://anikage.cc/api/media/anime/${slug}/episodes/${safeEpisode}/sources?provider=${s}&lang=${t}`, {
              headers: {
                'User-Agent': USER_AGENT,
                'Referer': `https://anikage.cc/watch/${slug}?ep=${safeEpisode}`,
                'Origin': 'https://anikage.cc'
              }
            });
            if (srcRes.status === 429) cooldownSeconds = Math.max(1, Number(srcRes.headers.get('retry-after')) || 60);
            if (!srcRes.ok) { console.log(`[Anikage] ${s} ${t} HTTP ${srcRes.status}`); return []; }
            const srcJson = await srcRes.json();
            if (srcJson.subType && srcJson.subType !== t) return [];
            const sources = srcJson.sources || [];
            const subtitles = (srcJson.subtitles || []).map(sub => ({
              url: sub.file ? (/^https?:\/\//i.test(sub.file) ? sub.file : `https://og.bakayaro.live/m3u8/${sub.file}`) : null,
              language: sub.label || 'en',
              name: sub.label || 'English'
            })).filter(sub => sub.url);

            const serverNameFormatted = s.charAt(0).toUpperCase() + s.slice(1);
            const isDub = t === 'dub';
            const results = [];

            for (const src of sources) {
              if (!src.url) continue;
              const isM3u8 = src.isM3U8 !== false;
              const streamUrl = /^https?:\/\//i.test(src.url) ? src.url : `https://og.bakayaro.live/${isM3u8 ? 'm3u8' : 'stream'}/${src.url}`;
              const mediaHeaders = Object.assign({
                'Referer': 'https://anikage.cc/', 'Origin': 'https://anikage.cc', 'User-Agent': USER_AGENT
              }, srcJson.headers || {}, src.headers || {});
              // Sources can contain an embed or an expired resolver response. Only expose media.
              const media = await morrowMediaFetch(streamUrl, { method: isM3u8 ? "GET" : "HEAD", headers: mediaHeaders });
              if (!media.ok) continue;
              const contentType = media.headers.get('content-type') || '';
              const manifest = /mpegurl/i.test(contentType) || isM3u8 ? await media.text() : '';
              if (isM3u8 ? !manifest.trimStart().startsWith('#EXTM3U') : !/^video\//i.test(contentType)) continue;
              const actualServerName = src.label || src.quality || serverNameFormatted;
              const quality = src.resolution || 'Auto';
              const typeTag = isDub ? '[DUB]' : '[SUB]';
              const langDisplay = isDub ? '🗣️ English Dub' : '🇯🇵 Japanese Sub';

              results.push({
                name: `${serverNameFormatted} / ${actualServerName} ${typeTag} • ${quality}`,
                title: `${cleanTitle} · Episode ${safeEpisode} | Anikage • ${serverNameFormatted} ${typeTag} • ${quality} | ${langDisplay}`,
                url: streamUrl,
                quality: quality,
                language: isDub ? 'en' : 'ja',
                type: isM3u8 ? 'm3u8' : 'mp4',
                provider: 'Anikage',
                headers: mediaHeaders,
                subtitles: subtitles
              });
            }

            return results;
          } catch (_) {
            console.log(`[Anikage] ${s} ${t}: ${_ && _.message || 'source unavailable'}`);
            return [];
          }
        });
      }
    }

    const settled = [];
    for (let offset = 0; offset < fetchTasks.length; offset += 4) {
      settled.push(...await Promise.all(fetchTasks.slice(offset, offset + 4).map(task => task())));
    }
    const allStreams = settled.flat();
    if (!allStreams.length && cooldownSeconds) throw new Error(`Anikage rate limit: try again in ${cooldownSeconds} seconds`);

    // Sort: 1080p first, then 720p, then others
    allStreams.sort((a, b) => {
      const qA = a.quality.includes('1080') ? 3 : a.quality.includes('720') ? 2 : 1;
      const qB = b.quality.includes('1080') ? 3 : b.quality.includes('720') ? 2 : 1;
      return qB - qA;
    });

    return (await Promise.all(allStreams.map(async stream =>
      stream.type !== "m3u8" || await morrowAccessibleHls(stream.url, stream.headers) ? stream : null))).filter(Boolean);
  } catch (err) {
    if (String(err && err.message).startsWith('Anikage rate limit:')) throw err;
    console.error(`[Anikage] ${err && err.message ? err.message : err}`);
    return [];
  }
}

module.exports = {
  getStreams
};
globalThis.getStreams = getStreams;

// Validate the actual first media route, not just an accessible master playlist.
async function morrowMediaFetch(url, options) {
  let timer;
  try {
    return await Promise.race([fetch(url, options), new Promise((_, reject) => {
      timer = setTimeout(() => reject(new Error('Media validation timeout')), 3500);
    })]);
  } finally { if (timer) clearTimeout(timer); }
}
async function morrowAccessibleHls(url, headers, playlist) {
  try {
    let base = url, body = playlist;
    for (let depth = 0; depth < 4; depth++) {
      if (!body) {
        const response = await morrowMediaFetch(base, {headers});
        if (!response.ok) return false;
        body = await response.text();
        base = response.url || base;
      }
      if (!body.trimStart().startsWith('#EXTM3U')) return false;
      const lines = body.split(/\r?\n/).map(line => line.trim());
      const variants = [];
      for (let i=0; i<lines.length; i++) if (lines[i].startsWith('#EXT-X-STREAM-INF:')) {
        const resolution = /RESOLUTION=(\d+)x(\d+)/i.exec(lines[i]);
        const bandwidth = /(?:^|,)BANDWIDTH=(\d+)/i.exec(lines[i].slice(18));
        const uri = lines.slice(i+1).find(line => line && !line.startsWith('#'));
        if (uri) variants.push({uri,area:resolution?Number(resolution[1])*Number(resolution[2]):0,bitrate:Number(bandwidth?.[1])||0});
      }
      if (variants.length) {
        variants.sort((a,b) => b.area-a.area || b.bitrate-a.bitrate);
        base = new URL(variants[0].uri, base).href; body = null; continue;
      }
      const media = lines.find(line => line && !line.startsWith('#'));
      if (!media) return false;
      const mediaUrl = new URL(media, base).href;
      let response = await morrowMediaFetch(mediaUrl, {headers:{...headers,Range:'bytes=0-1023'}});
      if (response.status === 400 || response.status === 416)
        response = await morrowMediaFetch(mediaUrl, {headers});
      const contentType = response.headers.get('content-type') || '';
      return response.ok && !/text\/html|application\/json/i.test(contentType);
    }
  } catch (_) { return false; }
  return false;
}
