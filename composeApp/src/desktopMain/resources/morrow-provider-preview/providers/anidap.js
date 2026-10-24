/** Shared implementation for the public catalogs used by Anidap, Anistream and AnimeX.
 * Each built adapter uses that site's own stream API. Catalog identity and episode
 * listing must agree before requesting any media; embeds are never native sources.
 */
const SITE = {"name":"Anidap","origin":"https://anidap.lol","api":"https://chad.anidap.lol/rest/api"};
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0 Safari/537.36';
const catalogOrigin = 'https://anidap.lol';
function norm(s) { return String(s || '').normalize('NFKD').replace(/[\u0300-\u036f]/g, '').toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim(); }
function context() { return typeof MORROW_MEDIA_CONTEXT === 'object' && MORROW_MEDIA_CONTEXT ? MORROW_MEDIA_CONTEXT : {}; }
async function request(url, options, ms) {
    const controller = typeof AbortController === 'function' ? new AbortController() : null;
    const timer = controller && setTimeout(() => controller.abort(), ms || 4000);
    try { return await fetch(url, { ...options, ...(controller ? { signal: controller.signal } : {}) }); }
    finally { if (timer) clearTimeout(timer); }
}
async function json(url, options) {
    const origin = url.startsWith(catalogOrigin + '/') ? catalogOrigin : SITE.origin;
    const r = await request(url, { ...options, headers: { Origin: origin, Referer: origin + '/', 'User-Agent': UA, Accept: 'application/json', ...(options && options.headers || {}) } });
    if (!r.ok) throw new Error('Source service HTTP ' + r.status);
    const payload = await r.json();
    if (payload === null) throw new Error('Invalid JSON from ' + new URL(url).hostname + new URL(url).pathname);
    return payload;
}
function detailTitles(d) { return [d.titleEnglish, d.titleRomaji, d.englishTitle, d.romajiTitle, d.title && d.title.english, d.title && d.title.romaji, ...Object.values(d.titles || {})].filter(Boolean); }
async function exactDetail(id, mediaType, season) {
    const ctx = context();
    let anilist = Number(ctx.anilistId) || Number((String(id).match(/^anilist:(\d+)$/) || [])[1]);
    const numericId = String(id).replace(/^tmdb:/, '');
    let tmdb = /^\d+$/.test(numericId) ? numericId : null;
    let title = ctx.animeTitle;
    if (!ctx.animeEpisode && mediaType !== 'movie' && Number(season) > 1) return null;
    if (!anilist) {
        let year = null;
        if (tmdb) {
            const key = globalThis.TMDB_API_KEY;
            if (!key) return null;
            const d = await json('https://api.themoviedb.org/3/' + (mediaType === 'movie' ? 'movie' : 'tv') + '/' + tmdb + '?api_key=' + encodeURIComponent(key));
            title = title || d.name || d.title;
            year = Number(String(d.first_air_date || d.release_date || '').slice(0, 4));
        } else if (!title && !/^(?:tt\d+|[a-z]+:\d+)$/.test(String(id))) title = String(id);
        if (!title) return null;
        const results = (await json(catalogOrigin + '/api/anime/search?q=' + encodeURIComponent(title))).results || [];
        let matches = results.filter(r => {
            const names = typeof r.title === 'object' ? Object.values(r.title || {}) : [r.title, r.name];
            const format = String(r.type || r.format || '').toUpperCase();
            return names.some(t => norm(t) === norm(title)) && (mediaType === 'movie' ? format === 'MOVIE' : format !== 'MOVIE');
        });
        if (matches.length > 1 && year) matches = matches.filter(r => Number(r.releaseDate) === year);
        if (matches.length !== 1) return null;
        anilist = Number(matches[0].id);
    }
    if (!Number.isInteger(anilist) || anilist <= 0) return null;
    let detail;
    if (SITE.graphql) {
        const payload = await json(SITE.graphql, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({
            query: 'query AnimeDetailBase($id: String, $anilistId: Int) { anime(id: $id, anilistId: $anilistId) { id anilistId malId tmdbId titleEnglish titleRomaji format episodeCount } }',
            variables: { anilistId: anilist }
        }) });
        detail = payload.data && payload.data.anime;
    } else detail = (await json(catalogOrigin + '/api/anime/' + anilist)).data;
    if (!detail || Number(detail.anilistId) !== anilist || !detail.id) return null;
    if (tmdb && !ctx.anilistId && String(detail.tmdbId) !== tmdb) return null;
    if (title && !ctx.anilistId && !detailTitles(detail).some(t => norm(t) === norm(title))) return null;
    const isMovie = String(detail.format || detail.type || '').toUpperCase() === 'MOVIE';
    if ((mediaType === 'movie') !== isMovie) return null;
    return detail;
}
function cleanUrl(s) { return String(s || '').replace(/^https?:\/{3,}/, m => m.startsWith('https:') ? 'https://' : 'http://'); }
async function validatedMediaType(url, headers) {
    try {
        const path = new URL(url).pathname;
        const directVideo = /\.(?:mp4|mkv|webm)$/i.test(path);
        const r = await request(url, { method: directVideo ? 'HEAD' : 'GET', headers }, 3000);
        if (!r.ok) return null;
        const mime = (r.headers.get('content-type') || '').toLowerCase();
        if (mime.startsWith('video/')) return /\.mkv$/i.test(path) ? 'mkv' : 'mp4';
        if (mime.includes('text/html')) return null;
        const text = (await r.text()).trimStart();
        if (text.startsWith('#EXTM3U')) return await morrowAccessibleHls(url, headers, text) ? 'm3u8' : null;
        if (/^(?:<\?xml[^>]*>\s*)?<MPD\b/i.test(text)) return 'mpd';
    } catch (_) {}
    return null;
}
function tracks(value) {
    if (typeof value === 'string') { try { return tracks(JSON.parse(value)); } catch (_) { return []; } }
    if (value && !Array.isArray(value)) return tracks(value.json || value.tracks);
    return Array.isArray(value) ? value : [];
}
async function getStreams(id, mediaType, season, episode) {
    try {
        const d = await exactDetail(id, mediaType || 'tv', season);
        const ep = Number(context().animeEpisode || (mediaType === 'movie' ? 1 : episode));
        if (!d || !Number.isInteger(ep) || ep <= 0) return [];
        const listed = await json(SITE.api + '/episodes?id=' + encodeURIComponent(d.id));
        const episodes = Array.isArray(listed) ? listed : listed.episodes || listed.data;
        if (!Array.isArray(episodes) || !episodes.some(e => Number(e.number || e.episode || e.episode_number) === ep)) return [];
        const list = await json(SITE.api + '/servers?id=' + encodeURIComponent(d.id) + '&epNum=' + ep);
        const servers = [...(list.subProviders || []).map(s => ({ ...s, track: 'sub' })), ...(list.dubProviders || []).map(s => ({ ...s, track: 'dub' }))];
        const seenServers = new Set();
        const results = await Promise.allSettled(servers.filter(s => {
            const key = s.track + ':' + s.id;
            if (!s.id || seenServers.has(key)) return false;
            seenServers.add(key); return true;
        }).map(async server => {
            const sourceData = await json(SITE.api + '/sources?id=' + encodeURIComponent(d.id) + '&epNum=' + ep + '&providerId=' + encodeURIComponent(server.id) + '&type=' + server.track + (server.track === 'dub' ? '&isDub=true' : ''));
            if (sourceData.episode_number != null && Number(sourceData.episode_number) !== ep) return [];
            const captions = tracks(sourceData.tracks);
            return await Promise.all((sourceData.sources || []).map(async source => {
                const url = cleanUrl(source.url || source.file);
                if (!/^https?:\/\//.test(url)) return null;
                const headers = { 'User-Agent': UA, ...(sourceData.headers || {}), ...(source.headers || {}) };
                delete headers.Range;
                const type = await validatedMediaType(url, headers);
                if (!type) return null;
                const multi = Array.isArray(sourceData.audio) && sourceData.audio.length > 1;
                const tag = multi ? 'Multi-audio' : server.track === 'sub' ? 'SUB' : 'DUB';
                const quality = /^(?:2160|1440|1080|720|480|360)p$/i.test(String(source.quality || '')) ? source.quality : 'Auto';
                return { name: SITE.name + ' | ' + (server.name || server.id) + ' [' + tag + '] · ' + quality,
                    title: (d.titleEnglish || d.titleRomaji) + ' · Episode ' + ep, url, type, quality,
                    language: multi ? 'und' : server.track === 'sub' ? 'ja' : 'en', provider: SITE.name, headers,
                    subtitles: captions.filter(t => t.kind !== 'thumbnails').map(t => ({ url: cleanUrl(t.url || t.file), name: t.label || t.name || t.lang, language: t.lang || t.language || 'und', headers: { ...headers, ...(t.headers || {}) } })).filter(t => /^https?:\/\//.test(t.url)) };
            }));
        }));
        const seen = new Set();
        return results.filter(r => r.status === 'fulfilled').flatMap(r => r.value).filter(s => {
            if (!s) return false;
            const key = s.language + ':' + s.url + ':' + JSON.stringify(s.headers);
            if (seen.has(key)) return false; seen.add(key); return true;
        });
    } catch (e) { console.log('[' + SITE.name + '] ' + (e && e.message || e)); return []; }
}
module.exports = { getStreams };

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
