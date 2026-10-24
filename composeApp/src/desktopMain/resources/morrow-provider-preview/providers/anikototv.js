/** Genuine HiAnime catalog and episode API. No streams from other providers. */
const HI_ROOT = 'https://anikototv.to';
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

/* Genuine Anikoto catalog and per-episode server routes. Helpers come from HiAnime's public host resolver. */
function kotoText(s){return String(s||'').replace(/<[^>]*>/g,' ').replace(/&amp;/g,'&').replace(/&#039;|&apos;/g,"'").replace(/&quot;/g,'"').replace(/\s+/g,' ').trim();}
async function kotoJson(path,referer){const r=await fetch(HI_ROOT+path,{headers:{Referer:referer||HI_ROOT+'/home','X-Requested-With':'XMLHttpRequest',Accept:'application/json'}});if(!r.ok)return null;const j=await r.json();return j&&Number(j.status)===200?j.result:null;}
function kotoCards(html,aliases,movie){
 const hits=[];
 for(const block of html.split(/<div\b[^>]*class=["']item\b[^"']*["']/i).slice(1)){
  const link=block.match(/<a\b[^>]*class=["'][^"']*\bname\s+d-title\b[^"']*["'][^>]*>([\s\S]*?)<\/a>/i);if(!link)continue;
  const tag=link[0].slice(0,link[0].indexOf('>')+1),title=kotoText(link[1]),japanese=hiAttr(tag,'data-jp');
  const id=(block.match(/data-tip=["'](\d+)/i)||[])[1],format=(block.match(/class=["']right["']>\s*(TV|Movie|OVA|ONA|Special)\s*</i)||[])[1];
  if(!id||!format||(format==='Movie')!==movie||!aliases.some(t=>[title,japanese].some(n=>hiNorm(n)===hiNorm(t))))continue;
  const url=hiAttr(tag,'href');if(url&&!hits.some(v=>v.id===id))hits.push({id,url,title});
 }
 return hits;
}
function kotoServers(html){const result=[];for(const part of html.split(/<div\b[^>]*class=["']type\b[^"']*["']/i).slice(1)){
 const language=hiAttr(part.split('>')[0],'data-type');if(!['sub','dub'].includes(language))continue;
 for(const m of part.matchAll(/<li\b([^>]*)>([\s\S]*?)<\/li>/gi)){
  const token=hiAttr(m[1],'data-link-id');if(token)result.push({token,name:kotoText(m[2]).replace(/\s*beta$/i,''),language,episodeId:hiAttr(m[1],'data-ep-id')});
 }}return result.filter((v,i,a)=>a.findIndex(s=>s.token===v.token&&s.language===v.language)===i);}
async function getStreams(id,type='tv',season=1,episode=1){
 try{
  const ctx=globalThis.MORROW_MEDIA_CONTEXT||{};if(type!=='movie'&&Number(season)>1&&!ctx.animeEpisode)return [];
  let aliases=[ctx.animeTitle].filter(Boolean);
  if(!aliases.length&&globalThis.TMDB_API_KEY&&/^\d+$/.test(String(id).replace(/^tmdb:/,''))){const d=await hiJson('https://api.themoviedb.org/3/'+(type==='movie'?'movie':'tv')+'/'+String(id).replace(/^tmdb:/,'')+'?api_key='+encodeURIComponent(globalThis.TMDB_API_KEY));aliases=[d.name,d.title,d.original_name,d.original_title].filter(Boolean);}
  if(!aliases.length&&!/^(?:tt\d+|[a-z]+:\d+|\d+)$/.test(String(id)))aliases=[String(id)];
  const ep=Number(ctx.animeEpisode||(type==='movie'?1:episode));if(!aliases.length||!Number.isInteger(ep)||ep<1)return [];
  const hits=[];for(const title of [...new Set(aliases)].slice(0,3))for(const hit of kotoCards(await hiText(HI_ROOT+'/filter?keyword='+encodeURIComponent(title)),aliases,type==='movie'))if(!hits.some(h=>h.id===hit.id))hits.push(hit);
  if(hits.length!==1)return [];const show=hits[0];
  const listing=await kotoJson('/ajax/episode/list/'+show.id+'?style=list',show.url);if(typeof listing!=='string')return [];
  const episodes=(listing.match(/<a\b[^>]*>/gi)||[]).filter(t=>Number(hiAttr(t,'data-num')||hiAttr(t,'data-number'))===ep&&hiAttr(t,'data-ids'));
  if(episodes.length!==1)return [];const ids=hiAttr(episodes[0],'data-ids');
  const roster=await kotoJson('/ajax/server/list?servers='+encodeURIComponent(ids),show.url);if(typeof roster!=='string')return [];
  const outputs=await Promise.all(kotoServers(roster).map(async server=>{try{
   const resolved=await kotoJson('/ajax/server?get='+encodeURIComponent(server.token),show.url),embed=resolved&&resolved.url;if(!/^https:\/\//.test(embed||''))return null;
   const html=await hiText(embed,show.url),data=await hiMegaSource(embed,html);if(!data||!/^https:\/\//.test(data.src||''))return null;
   const origin=new URL(embed).origin;
   // Browser strict-origin-when-cross-origin sends only the player origin to this CDN.
   const headers={Referer:origin+'/',Origin:origin,'User-Agent':'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0 Safari/537.36'};if(!await hiPlayable(data.src,headers))return null;
   return {name:'Anikoto | '+server.name+' ['+server.language.toUpperCase()+']',title:show.title+' · Episode '+ep,provider:'Anikoto',url:data.src,type:'m3u8',quality:'Auto',language:server.language==='dub'?'en':'ja',headers,subtitles:(data.subtitles||[]).filter(t=>t.src||t.url).map(t=>({url:t.src||t.url,language:t.lang||t.language||'und',name:t.label||'Subtitles',headers}))};
  }catch(_){return null;}}));return outputs.filter(Boolean);
 }catch(_){return [];}
}
module.exports={getStreams};globalThis.getStreams=getStreams;
