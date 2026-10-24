// Morrow adapter for the actual kaa.lt catalog and its published HLS player manifests.
const KAA_ROOT = 'https://kaa.lt';
const KAA_TMDB_KEY = '439c478a771f35c05022f9feabcca01c';
const kaaNorm = s => String(s || '').toLowerCase().replace(/[^a-z0-9]/g, '');
async function kaaJson(url, options) {
  const r = await fetch(url, options);
  if (!r.ok) throw new Error('KAA request failed: ' + r.status);
  return r.json();
}
function kaaAstro(value) {
  if (Array.isArray(value)) {
    if (value[0] === 1) return (value[1] || []).map(kaaAstro);
    if (value[0] === 0) return kaaAstro(value[1]);
    return value.map(kaaAstro);
  }
  if (value && typeof value === 'object') {
    const result = {};
    for (const key of Object.keys(value)) result[key] = kaaAstro(value[key]);
    return result;
  }
  return value;
}
async function getStreams(id, mediaType = 'tv', season = 1, episode = 1) {
  try {
    const context = globalThis.MORROW_MEDIA_CONTEXT || {};
    if (!context.anilistId && Number(season) > 1) return [];
    let title = context.animeTitle;
    if (!title) {
      const lookup = String(id).replace(/^tmdb:/, '');
      if (!/^\d+$/.test(lookup)) return [];
      const meta = await kaaJson('https://api.themoviedb.org/3/' + (mediaType === 'movie' ? 'movie' : 'tv') + '/' + lookup + '?api_key=' + KAA_TMDB_KEY);
      title = meta.name || meta.title || meta.original_name || meta.original_title;
    }
    const search = await kaaJson(KAA_ROOT + '/api/fsearch', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ page: 1, query: title }),
    });
    const matches = (search.result || []).filter(show =>
      [show.title, show.title_en].some(t => kaaNorm(t) === kaaNorm(title)) &&
      (mediaType === 'movie' ? show.type === 'movie' : show.type !== 'movie'));
    if (matches.length !== 1) return [];
    const show = matches[0];
    const ep = mediaType === 'movie' ? 1 : Number(context.animeEpisode || episode);
    if (!Number.isInteger(ep) || ep < 1) return [];
    const catalog = await kaaJson(KAA_ROOT + '/api/show/' + show.slug + '/episodes?ep=' + ep + '&lang=ja-JP');
    const selected = (catalog.result || []).find(item => Number(item.episode_number) === ep);
    if (!selected) return [];
    const detail = await kaaJson(KAA_ROOT + '/api/show/' + show.slug + '/episode/ep-' + ep + '-' + selected.slug);
    if (detail.show_slug !== show.slug || Number(detail.episode_number) !== ep) return [];
    const streams = await Promise.all((detail.servers || []).map(async server => {
      try {
        const embed = await fetch(server.src, { headers: { Referer: KAA_ROOT + '/' } });
        if (!embed.ok) return null;
        const html = await embed.text();
        const props = /<astro-island\b[^>]*\bprops="([^"]+)"/i.exec(html);
        if (!props) return null;
        const decoded = props[1].replace(/&quot;/g, '"').replace(/&#39;|&apos;/g, "'").replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>');
        const player = kaaAstro(JSON.parse(decoded));
        const manifest = String(player.manifest || '').replace(/^https?:\/{3,}/, 'https://');
        if (!manifest.endsWith('.m3u8')) return null;
        const headers = { Referer: new URL(server.src).origin + '/', Origin: new URL(server.src).origin };
        const response = await fetch(manifest, { headers });
        if (!response.ok) return null;
        const playlist = await response.text();
        if (!playlist.trimStart().startsWith('#EXTM3U')) return null;
        const multiAudio = /#EXT-X-MEDIA:.*TYPE=AUDIO/.test(playlist);
        return {
          name: server.name + (multiAudio ? ' [Multi audio]' : ' [SUB]'),
          title: title + ' • Episode ' + ep + ' • ' + server.name,
          provider: 'KickAssAnime', url: manifest, type: 'm3u8', quality: 'Auto',
          language: multiAudio ? 'und' : 'ja', headers,
          subtitles: (player.subtitles || []).filter(s => s.src).map(s => ({ url: s.src, language: s.language || 'und', name: s.name })),
        };
      } catch (_) { return null; }
    }));
    return streams.filter(Boolean);
  } catch (_) { return []; }
}
globalThis.getStreams = getStreams;
module.exports = { getStreams };
