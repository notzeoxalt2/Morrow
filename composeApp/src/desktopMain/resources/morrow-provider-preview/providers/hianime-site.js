/** Genuine HiAnime catalog and episode API. No streams from other providers. */
const HI_ROOT = 'https://hianime.at';
function hiNorm(s) { return String(s || '').normalize('NFKD').replace(/[\u0300-\u036f]/g, '').toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim(); }
function hiAttr(s, name) { const m = s.match(new RegExp('(?:^|\\s)' + name + '=["\x27]([^"\x27]*)["\x27]', 'i')); return m ? m[1].replace(/&amp;/g, '&').replace(/&#039;/g, "'").replace(/&quot;/g, '"') : ''; }
function hiBase64(s) {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  let bits = 0, value = 0, out = '';
  for (const c of s.replace(/=+$/, '')) { const n = alphabet.indexOf(c); if (n < 0) continue; value = (value << 6) | n; bits += 6; if (bits >= 8) { bits -= 8; out += String.fromCharCode((value >>> bits) & 255); } }
  return out;
}
async function hiText(url, referer = HI_ROOT + '/') {
  const r = await fetch(url, { headers: { Referer: referer, Accept: '*/*' } });
  if (!r.ok) throw new Error('HiAnime HTTP ' + r.status);
  return r.text();
}
async function hiJson(url) { return JSON.parse(await hiText(url)); }
async function hiMegaSource(embed, html) {
  const origin = new URL(embed).origin;
  const fileId = (html.match(/data-id=["']([^"']+)/) || [])[1]; if (!fileId) return null;
  const clientPath = (html.match(/<script\b[^>]*src=["']([^"']*newclient[^"']+)/) || [])[1]; if (!clientPath) return null;
  const client = await hiText(new URL(clientPath, origin).href, embed);
  const keyText = (client.match(/trustAesKey[^\n]+?\],"([^"]+)"/) || [])[1];
  const ivText = (client.match(/trustAesIv[^\n]+?\],"([^"]+)"/) || [])[1];
  if (!keyText || !ivText || !globalThis.crypto?.subtle) return null;
  const route = (client.match(/["'](stream\/getSourcesNew)["']/) || [])[1]; if (!route) return null;
  const response = await fetch(origin + '/' + route + '?id=' + encodeURIComponent(fileId) + '&id=' + encodeURIComponent(fileId), { headers: { Referer: embed, 'X-Requested-With': 'XMLHttpRequest' } });
  if (!response.ok) return null;
  const data = await response.json();
  let src = data.sources?.file;
  if (!src && data.enc) {
    const keyBytes = new Uint8Array(32), ivBytes = new Uint8Array(16);
    keyBytes.set(new TextEncoder().encode(keyText).subarray(0, 32));
    ivBytes.set(new TextEncoder().encode(ivText).subarray(0, 16));
    const key = await crypto.subtle.importKey('raw', keyBytes, { name: 'AES-CBC' }, false, ['decrypt']);
    const raw = hiBase64(String(data.enc).replace(/-/g, '+').replace(/_/g, '/'));
    const bytes = Uint8Array.from(raw, c => c.charCodeAt(0));
    const decoded = JSON.parse(new TextDecoder().decode(await crypto.subtle.decrypt({ name: 'AES-CBC', iv: ivBytes }, key, bytes)));
    src = decoded.file || decoded.url;
  }
  return src ? { src, subtitles: (data.tracks || []).filter(t => t.kind === 'captions' || t.kind === 'subtitles').map(t => ({ src: t.file, label: t.label, lang: /^English/i.test(t.label || '') ? 'en' : 'und' })) } : null;
}
async function hiPlayable(url, headers) {
  for (let depth = 0; depth < 4; depth++) {
    const r = await fetch(url, { headers }); if (!r.ok) return false;
    const body = await r.text(); if (!body.trimStart().startsWith('#EXTM3U')) return false;
    const lines = body.split(/\r?\n/).map(s => s.trim());
    const variants = [];
    for (let i = 0; i < lines.length - 1; i++) if (lines[i].startsWith('#EXT-X-STREAM-INF:')) {
      const next = lines.slice(i + 1).find(s => s && !s.startsWith('#'));
      if (next) variants.push({ url: new URL(next, url).href, rate: Number((lines[i].match(/BANDWIDTH=(\d+)/) || [])[1] || 0) });
    }
    if (variants.length) { url = variants.sort((a, b) => b.rate - a.rate)[0].url; continue; }
    const segment = lines.find(s => s && !s.startsWith('#')); if (!segment) return false;
    let probe = await fetch(new URL(segment, url).href, { headers: { ...headers, Range: 'bytes=0-1023' } });
    if (probe.status === 400 || probe.status === 416) probe = await fetch(new URL(segment, url).href, { headers });
    return probe.ok && !/text\/html|application\/json/i.test(probe.headers.get('content-type') || '');
  }
  return false;
}
async function getStreams(id, mediaType = 'tv', season = 1, episode = 1) {
  try {
    const context = globalThis.MORROW_MEDIA_CONTEXT || {};
    if (!context.anilistId && Number(season) > 1) return [];
    let aliases = [context.animeTitle].filter(Boolean);
    if (!aliases.length && globalThis.TMDB_API_KEY && /^\d+$/.test(String(id).replace(/^tmdb:/, ''))) {
      const meta = await hiJson('https://api.themoviedb.org/3/' + (mediaType === 'movie' ? 'movie' : 'tv') + '/' + String(id).replace(/^tmdb:/, '') + '?api_key=' + globalThis.TMDB_API_KEY);
      aliases = [meta.name, meta.title, meta.original_name, meta.original_title].filter(Boolean);
    }
    if (!aliases.length) return [];
    const number = mediaType === 'movie' ? 1 : Number(context.animeEpisode || episode);
    if (!Number.isInteger(number) || number < 1) return [];
    const hits = new Map();
    for (const title of [...new Set(aliases)].slice(0, 3)) {
      const html = await hiText(HI_ROOT + '/search?keyword=' + encodeURIComponent(title));
      for (const block of html.split(/<div\b[^>]*class="flw-item\b/).slice(1)) {
        const anchor = block.match(/<a\b[^>]*class="dynamic-name"[^>]*>/); if (!anchor) continue;
        const href = hiAttr(anchor[0], 'href'), names = [hiAttr(anchor[0], 'title'), hiAttr(anchor[0], 'data-jname')];
        const siteId = (href.match(/-(\d+)\/?$/) || [])[1];
        const format = (block.match(/class="fdi-item">\s*(Movie|TV|ONA|OVA|Special)\s*</i) || [])[1];
        if (siteId && names.some(n => aliases.some(a => hiNorm(n) === hiNorm(a))) && format && ((mediaType === 'movie') === (format.toLowerCase() === 'movie'))) hits.set(siteId, { href, title: names[0] || title });
      }
    }
    if (hits.size !== 1) return [];
    const [siteId, show] = [...hits][0];
    const episodes = await hiJson(HI_ROOT + '/api/theme/episode/list/' + siteId);
    const matching = (episodes.html || '').match(/<a\b[^>]*>/g)?.filter(a => Number(hiAttr(a, 'data-number')) === number && hiAttr(a, 'data-id')) || [];
    if (matching.length !== 1) return [];
    const epId = hiAttr(matching[0], 'data-id'), watch = hiAttr(matching[0], 'href');
    const servers = await hiJson(HI_ROOT + '/api/theme/episode/servers?episodeId=' + encodeURIComponent(epId));
    const options = (servers.html || '').match(/<div\b[^>]*data-server-name[^>]*>/g) || [];
    const output = await Promise.all(options.map(async tag => {
      try {
        const audio = hiAttr(tag, 'data-type'), server = hiAttr(tag, 'data-server-name');
        if (!['sub', 'dub'].includes(audio)) return null;
        const embed = hiBase64(hiAttr(tag, 'data-hash'));
        const identity = embed.match(/^https:\/\/zokoanime\.video\/stream\/mal\/(\d+)\/(\d+)\/(sub|dub)(?:\?|$)/);
        const mega = /^https:\/\/megaplay\.buzz\/stream\/s-2\/\d+\/(sub|dub)(?:\?|$)/.test(embed);
        if (!mega && (!identity || Number(identity[2]) !== number || identity[3] !== audio)) return null;
        if (mega && !embed.includes('/' + audio + '?') && !embed.endsWith('/' + audio)) return null;
        const expectedMal = String(context.originalId || '').match(/^mal:(\d+)$/);
        if (identity && expectedMal && Number(expectedMal[1]) !== Number(identity[1])) return null;
        const html = await hiText(embed, watch);
        let data;
        if (mega) { data = await hiMegaSource(embed, html); if (!data) return null; }
        else {
          const blob = (html.match(/window\.__P\s*=\s*["']([^"']+)/) || [])[1]; if (!blob) return null;
          const raw = hiBase64(blob), key = 'otaku-embed-v1';
          let encoded = ''; for (let i = 0; i < raw.length; i++) encoded += '%' + (raw.charCodeAt(i) ^ key.charCodeAt(i % key.length)).toString(16).padStart(2, '0');
          data = JSON.parse(decodeURIComponent(encoded));
        }
        if (!/^https:\/\//.test(data.src || '')) return null;
        const origin = new URL(embed).origin;
        const headers = mega ? { Referer: origin + '/', Origin: origin, 'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0 Safari/537.36' } : { Referer: embed, Origin: origin };
        if (!await hiPlayable(data.src, headers)) return null;
        return { provider: 'HiAnime', name: 'HiAnime | ' + server + ' [' + audio.toUpperCase() + ']', title: show.title + ' • Episode ' + number, url: data.src, type: 'm3u8', quality: 'Auto', language: audio === 'dub' ? 'en' : 'ja', headers, subtitles: (data.subtitles || []).filter(s => s.src || s.url).map(s => ({ url: s.src || s.url, language: s.language || s.lang || 'und', name: s.label || s.language || 'Subtitle', headers })) };
      } catch (_) { return null; }
    }));
    return output.filter(Boolean);
  } catch (_) { return []; }
}
globalThis.getStreams = getStreams;
module.exports = { getStreams };
