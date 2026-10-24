/** JustAnime's own catalog, episode and server API. No substitute providers. */
const JA_API = 'https://core.justanime.to/api';
const JA_HEADERS = {Origin:'https://www.justanime.to', Referer:'https://www.justanime.to/'};
function jaNorm(value) { return String(value || '').toLowerCase().replace(/[^a-z0-9]/g, ''); }
async function jaFetch(url, options) {
  let timer;
  try {
    return await Promise.race([fetch(url, options), new Promise((_, reject) => {
      timer = setTimeout(() => reject(new Error('JustAnime request timed out')), 6000);
    })]);
  } finally { clearTimeout(timer); }
}
async function jaJson(path) {
  const response = await jaFetch(JA_API + path, {headers:JA_HEADERS});
  if (!response.ok) throw new Error('JustAnime API HTTP ' + response.status);
  const value = await response.json();
  if (!value) throw new Error('JustAnime invalid API response');
  return value;
}
async function getStreams(id, mediaType='tv', season=1, episode=1) {
  try {
    const context = globalThis.MORROW_MEDIA_CONTEXT || {};
    const ep = Number(context.animeEpisode || episode || 1);
    if (!Number.isInteger(ep) || ep < 1) return [];
    let animeId = Number(context.anilistId) || (/^anilist:\d+$/.test(String(id)) ? Number(String(id).split(':')[1]) : 0);
    let title = String(context.animeTitle || '').trim();
    let year = 0;
    if (!animeId) {
      if (Number(season) > 1) return [];
      if (!title && !/^\d+$|^tt\d+$/.test(String(id))) title = String(id);
      if (!title && /^\d+$/.test(String(id)) && typeof TMDB_API_KEY !== 'undefined' && TMDB_API_KEY) {
        const response = await jaFetch('https://api.themoviedb.org/3/' + (mediaType==='movie'?'movie':'tv')
          + '/' + encodeURIComponent(id) + '?api_key=' + encodeURIComponent(TMDB_API_KEY));
        if (!response.ok) return [];
        const metadata = await response.json();
        title = metadata?.name || metadata?.title || '';
        year = Number(String(metadata?.first_air_date || metadata?.release_date || '').slice(0,4)) || 0;
      }
      if (!title) return [];
      const results = (await jaJson('/search/suggestions?query=' + encodeURIComponent(title))).data || [];
      const matches = results.filter(item => Object.values(item.title || {}).some(value => jaNorm(value) === jaNorm(title))
        && (mediaType === 'movie' ? item.type === 'MOVIE' : item.type !== 'MOVIE')
        && (!year || Number(item.year) === year));
      if (matches.length !== 1) return [];
      animeId = Number(matches[0].id);
    }
    const detail = (await jaJson('/anime/' + animeId)).data;
    if (!detail || Number(detail.id) !== animeId) return [];
    if (mediaType === 'movie' && detail.format !== 'MOVIE') return [];
    if (mediaType !== 'movie' && detail.format === 'MOVIE') return [];
    title = detail.title?.english || detail.title?.romaji || title;
    const page = Math.floor((ep - 1) / 100) + 1;
    const episodes = await jaJson('/anime/' + animeId + '/episodes?page=' + page);
    if (Number(episodes.id) !== animeId || !(episodes.episodes || []).some(item => Number(item.number) === ep)) return [];
    const watch = await jaJson('/watch/' + animeId + '/episode/' + ep);
    if (Number(watch.anilistId) !== animeId || Number(watch.episode) !== ep || Number(watch.anime?.id) !== animeId) return [];
    const availability = await jaJson('/watch/' + animeId + '/episode/' + ep + '/servers');
    if (Number(availability.anilistId) !== animeId || Number(availability.episode) !== ep) return [];
    const results = await Promise.all(Object.entries(availability.servers || {}).map(async ([server, available]) => {
      if (!available.sub && !available.dub) return [];
      try {
        const data = await jaJson('/watch/' + animeId + '/episode/' + ep + '/' + encodeURIComponent(server));
        const streams = await Promise.all(['sub','dub'].map(async audio => {
          if (!available[audio] || !data[audio]) return [];
          const track = data[audio];
          return (await Promise.all((track.sources || []).map(async source => {
            if (!/^https?:\/\//i.test(source.url || '')) return null;
            const headers = Object.assign({}, JA_HEADERS, track.headers || {}, source.headers || {});
            const hls = source.isM3U8 === true || /\.m3u8(?:\?|$)/i.test(source.url);
            try {
              const response = await jaFetch(source.url, {method:hls?'GET':'HEAD',headers});
              if (!response.ok) return null;
              if (hls ? !(await response.text()).trimStart().startsWith('#EXTM3U')
                : !/^video\//i.test(response.headers.get('content-type') || '')) return null;
              return {provider:'JustAnime',name:server + ' [' + audio.toUpperCase() + '] · ' + (source.quality || 'Auto'),
                title:title + ' · Episode ' + ep, url:source.url, type:hls?'m3u8':'mp4',quality:source.quality || 'Auto',
                language:audio==='sub'?'ja':'en',headers,
                subtitles:(track.subtitles || track.tracks || []).filter(s => s.kind !== 'thumbnails' && /^https?:\/\//i.test(s.file || s.url || ''))
                  .map(s => ({url:s.file || s.url,name:s.label || s.lang || 'Subtitle',language:s.lang || s.label || 'und',headers}))};
            } catch (_) { return null; }
          }))).filter(Boolean);
        }));
        return streams.flat();
      } catch (_) { return []; }
    }));
    return results.flat().sort((a,b) => (a.language==='ja'?0:1)-(b.language==='ja'?0:1)
      || (parseInt(b.quality,10)||0)-(parseInt(a.quality,10)||0) || a.name.localeCompare(b.name));
  } catch (error) { console.error('[JustAnime] ' + error.message); return []; }
}
module.exports = {getStreams};
globalThis.getStreams = getStreams;
