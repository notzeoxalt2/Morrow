// Genuine AnimeHeaven adapter: exact catalog title, exact episode key, published MP4 sources.
const AH_ROOT = 'https://animeheaven.me';
const AH_UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0 Safari/537.36';
function ahText(s) {
  return String(s || '').replace(/<[^>]*>/g, '').replace(/&#(\d+);/g, (_,n) => String.fromCharCode(Number(n)))
    .replace(/&#x([0-9a-f]+);/gi, (_,n) => String.fromCharCode(parseInt(n,16)))
    .replace(/&amp;/g,'&').replace(/&quot;/g,'"').replace(/&#039;|&apos;/g,"'").replace(/&nbsp;/g,' ').trim();
}
function ahNorm(s) { return ahText(s).normalize('NFKD').replace(/[\u0300-\u036f]/g,'').toLowerCase().replace(/[^a-z0-9]+/g,' ').trim(); }
async function ahFetch(url, options) {
  let timer;
  try {
    const r = await Promise.race([
      fetch(url, { ...options, headers: { 'User-Agent': AH_UA, Referer: AH_ROOT+'/', ...(options && options.headers || {}) } }),
      new Promise((_,reject) => { timer=setTimeout(() => reject(new Error('AnimeHeaven request timed out')),3000); })
    ]);
    if (!r.ok) throw new Error('AnimeHeaven HTTP '+r.status);
    return r;
  } finally { if(timer!==undefined)clearTimeout(timer); }
}
async function ahIdentity(id, mediaType, season, episode) {
  const context = globalThis.MORROW_MEDIA_CONTEXT || {};
  if (!context.anilistId && Number(season)>1) return null;
  const ep = mediaType==='movie' ? 1 : Number(context.animeEpisode || episode || 1);
  if (!Number.isInteger(ep) || ep<1) return null;
  let title=context.animeTitle, aliases=[];
  if (title) aliases.push(title);
  if (!title) {
    const tmdb=String(id).replace(/^tmdb:/,'');
    if (!/^\d+$/.test(tmdb) || !globalThis.TMDB_API_KEY) return null;
    const meta=await (await ahFetch('https://api.themoviedb.org/3/'+(mediaType==='movie'?'movie':'tv')+'/'+tmdb+'?api_key='+globalThis.TMDB_API_KEY)).json();
    aliases=[meta.title,meta.name,meta.original_title,meta.original_name].filter(Boolean);
    title=aliases[0];
  }
  return title ? {title,aliases,ep} : null;
}
async function getStreams(id, mediaType='tv', season=1, episode=1) {
  try {
    const identity=await ahIdentity(id,mediaType,season,episode);
    if (!identity) return [];
    const expected=identity.aliases.map(ahNorm);
    const candidates=new Map();
    for (const query of [...new Set(identity.aliases)].slice(0,3)) {
      const html=await (await ahFetch(AH_ROOT+'/search.php?s='+encodeURIComponent(query))).text();
      const re=/<div\b[^>]*class=['"][^'"]*similarname[^'"]*['"][^>]*>\s*<a\b[^>]*href=['"](anime\.php\?[^'"]+)['"][^>]*>([\s\S]*?)<\/a>/gi;
      let m;
      while ((m=re.exec(html))) {
        const catalogTitle=ahText(m[2]);
        const base=catalogTitle.replace(/\s*\((?:English\s+)?Dub(?:bed)?\)\s*$/i,'');
        if (expected.includes(ahNorm(base))) candidates.set(m[1],{path:m[1],title:catalogTitle,dub:base!==catalogTitle});
      }
    }
    // One independent matching catalog entry per language avoids an ambiguous remake.
    const selected=[false,true].map(dub=>[...candidates.values()].filter(s=>s.dub===dub)).filter(s=>s.length===1).map(s=>s[0]);
    const result=[];
    for (const show of selected) {
      const watch=AH_ROOT+'/'+show.path;
      const page=await (await ahFetch(watch)).text();
      const declared=/<div\b[^>]*class=['"][^'"]*infotitle[^'"]*['"][^>]*>([\s\S]*?)<\/div>/i.exec(page);
      if (!declared || ahNorm(declared[1])!==ahNorm(show.title)) continue;
      const keys=[];
      const epRe=/<a\b[^>]*\bid\s*=\s*['"]([a-f0-9]{32})['"][^>]*>\s*<div\b[^>]*class=['"][^'"]*trackep0\s+watch[^'"]*['"][\s\S]*?<div\b[^>]*class\s*=\s*['"][^'"]*watch2[^'"]*['"]\s*>\s*(\d+)/gi;
      let match;
      while ((match=epRe.exec(page))) if (Number(match[2])===identity.ep) keys.push(match[1]);
      if ([...new Set(keys)].length!==1) continue;
      const key=keys[0];
      // This cookie is how the site's normal episode links select their gate page.
      const gate=await (await ahFetch(AH_ROOT+'/gate.php',{headers:{Referer:watch,Cookie:'key='+key}})).text();
      const sourceRe=/<source\b[^>]*src=['"]([^'"]+)['"][^>]*type=['"]video\/mp4['"]/gi;
      const urls=[];
      while ((match=sourceRe.exec(gate))) {
        const url=ahText(match[1]);
        if (/^https:\/\/[^/]+\.animeheaven\.me\/video\.mp4\?/.test(url) && new URL(url).search.slice(1).split('&')[0]===key && !urls.includes(url)) urls.push(url);
      }
      const mediaHeaders={Referer:AH_ROOT+'/gate.php','User-Agent':AH_UA};
      const sources=await Promise.all(urls.map(async url=>{
        try {
          const response=await ahFetch(url,{method:'HEAD',headers:mediaHeaders});
          if (!/^video\//i.test(response.headers.get('content-type')||'')) return null;
          const server=new URL(url).hostname.split('.')[0].toUpperCase();
          return {provider:'AnimeHeaven',name:'AnimeHeaven | '+server+' ['+(show.dub?'DUB':'SUB')+']',
            title:show.title+' • Episode '+identity.ep+' • '+server,url,type:'mp4',quality:'Auto',language:show.dub?'en':'ja',headers:mediaHeaders};
        } catch (_) { return null; }
      }));
      result.push(...sources.filter(Boolean));
    }
    return result;
  } catch (_) { return []; }
}
globalThis.getStreams=getStreams;
module.exports={getStreams};
