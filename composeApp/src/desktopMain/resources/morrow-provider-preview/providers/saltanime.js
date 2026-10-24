const SALT_CONFIG = {"name":"SaltAnime","base":"https://saltanime.in","search":"php"};
/* Morrow: actual catalog pages and ordinary media hosts; no embed-as-media fallback. */
const SALT_UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36';
const SALT_LANGUAGE = {english:'en',japanese:'ja',hindi:'hi',tamil:'ta',telugu:'te',kannada:'kn',malayalam:'ml',bengali:'bn',marathi:'mr'};
function saltText(s) { return String(s || '').replace(/<[^>]*>/g,' ').replace(/&#(x[0-9a-f]+|\d+);/gi,(_,n)=>String.fromCharCode(n[0].toLowerCase()==='x'?parseInt(n.slice(1),16):Number(n))).replace(/&amp;/g,'&').replace(/&quot;/g,'"').replace(/&#0?39;|&apos;/g,"'").replace(/&nbsp;/g,' ').replace(/\s+/g,' ').trim(); }
function saltNorm(s) { return saltText(s).toLowerCase().replace(/[^a-z0-9]/g,''); }
function saltUrl(s,base) { try { const u=new URL(saltText(s).replace(/\\\//g,'/'),base); return /^https?:$/.test(u.protocol)?u.toString():null; } catch(_) {return null;} }
function saltBase64(s) { s=String(s).replace(/-/g,'+').replace(/_/g,'/'); while(s.length%4)s+='='; if(typeof atob==='function')return atob(s); const chars='ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/',bytes=[];let bits=0,value=0;for(const c of s){const n=chars.indexOf(c);if(n<0)continue;value=(value<<6)|n;bits+=6;if(bits>=8){bits-=8;bytes.push(String.fromCharCode((value>>bits)&255));}}return bytes.join(''); }
async function saltFetch(url,options) { /* Morrow already bounds native fetches; its cleared timer jobs can still delay evaluation. */ if(globalThis.SCRAPER_ID)return fetch(url,options); let timer; try { return await Promise.race([fetch(url,options),new Promise((_,reject)=>{timer=setTimeout(()=>reject(new Error('request timeout')),12000);})]); } finally {if(timer)clearTimeout(timer);} }
function saltHeaders(referer) { const u=new URL(referer);return {'User-Agent':SALT_UA,Referer:referer,Origin:u.origin}; }
function saltLiteral(s) { return s.replace(/\\(?:x([0-9a-f]{2})|u([0-9a-f]{4})|([\\'"/nrt]))/gi,(_,x,u,c)=>x||u?String.fromCharCode(parseInt(x||u,16)):({n:'\n',r:'\r',t:'\t'}[c]||c)); }
function saltUnpack(html) { return html.replace(/}\s*\(\s*'((?:\\.|[^'\\])*)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'((?:\\.|[^'\\])*)'\.split\('\|'\)/g,(_,p,radix,count,words)=>{const table=saltLiteral(words).split('|'),r=Number(radix);if(r<2||r>62||Number(count)>10000)return '';const num=s=>{let n=0;for(const c of s){const v=c>='a'&&c<='z'?c.charCodeAt(0)-87:c>='A'&&c<='Z'?c.charCodeAt(0)-29:Number(c);if(!Number.isFinite(v)||v>=r)return -1;n=n*r+v;}return n;};return saltLiteral(p).replace(/\b[0-9a-zA-Z]+\b/g,t=>{const n=num(t);return n>=0&&table[n]?table[n]:t;});}); }
function saltHostIdentity(html,target) {
  const match=html.match(/<title[^>]*>([\s\S]*?)<\/title>/i);if(!match)return true;
  const title=saltText(match[1]).replace(/\+/g,' ').replace(/\[[^\]]*\]/g,'').trim();
  // File titles are independent evidence. A site's wrongly attached upload must never play.
  const ep=title.match(/(?:\bS(\d{1,3})E(\d{1,5})\b|\b(?:episode|ep)\s*[-.:]?\s*(\d{1,5})\b|\b(\d{1,3})x(\d{1,5})\b)/i);
  if(!ep)return true;
  const uploadTitle=title.slice(0,ep.index).replace(/[\s._:-]+$/,'');
  if(!target.titles.some(t=>saltNorm(t)&&saltNorm(t)===saltNorm(uploadTitle)))return false;
  const uploadEpisode=Number(ep[2]||ep[3]||ep[5]);
  if(uploadEpisode!==target.sourceEpisode&&uploadEpisode!==target.relativeEpisode)return false;
  if(ep[1]&&Number(ep[1])!==target.sourceSeason)return false;
  return true;
}
async function saltMetadata(id,type,episode) {
  const context=globalThis.MORROW_MEDIA_CONTEXT||{},titles=[];
  if(context.animeTitle)titles.push(context.animeTitle);
  const raw=String(id).replace(/^tmdb:/,'');
  if(!/^\d+$/.test(raw)&&!/^tt\d+$/.test(raw)) {if(!/^(?:anilist|mal|kitsu|tmdb):/i.test(raw))titles.push(raw);}
  else {
    const key=globalThis.TMDB_API_KEY;
    if(key){const endpoint=/^tt/.test(raw)?'find/'+raw:'/'+(type==='movie'?'movie':'tv')+'/'+raw;try{const r=await saltFetch('https://api.themoviedb.org/3/'+endpoint.replace(/^\//,'')+'?api_key='+encodeURIComponent(key)+(/^tt/.test(raw)?'&external_source=imdb_id':''));if(r.ok){let j=await r.json();if(/^tt/.test(raw)){const list=type==='movie'?j.movie_results:j.tv_results;if(!list||list.length!==1)return null;j=list[0];}for(const t of [j.name,j.title,j.original_name,j.original_title])if(t&&!titles.includes(t))titles.push(t);}}catch(_) {}}
  }
  if(!titles.length)return null;
  const absolute=Number(context.animeEpisode);
  return {titles,tv:type!=='movie',absolute:Number.isInteger(absolute)&&absolute>0?absolute:null,relativeEpisode:Math.max(1,parseInt(episode,10)||1)};
}
function saltPageLinks(html,base,category) { const out=[];for(const m of html.matchAll(/<a\b[^>]*\bhref\s*=\s*["']([^"']+)["'][^>]*>([\s\S]*?)<\/a>/gi)){const u=saltUrl(m[1],base);if(!u)continue;const path=new URL(u).pathname;if(!new RegExp('^/(?:'+category+')/[^/]+/?$').test(path))continue;const slug=path.split('/')[2];if(!out.some(v=>v.url===u))out.push({url:u,slug,label:saltText(m[2])});}return out; }
async function saltCatalog(target) {
  const hits=[];
  for(const title of target.titles.filter(t=>saltNorm(t)).slice(0,3)){const search=SALT_CONFIG.base+(SALT_CONFIG.search==='php'?'/search.php?search=':'/?s=')+encodeURIComponent(title);try{const r=await saltFetch(search,{headers:saltHeaders(SALT_CONFIG.base+'/')});if(!r.ok)continue;const html=await r.text();for(const link of saltPageLinks(html,r.url||search,target.tv?'series|anime':'movie|movies')){if(target.titles.some(t=>saltNorm(t)&&(saltNorm(t)===saltNorm(link.slug)||saltNorm(t)===saltNorm(link.label)))&&!hits.some(v=>v.url.replace(/\/$/,'')===link.url.replace(/\/$/,'')))hits.push(link);}}catch(_) {}}
  if(hits.length!==1)return null;
  const r=await saltFetch(hits[0].url,{headers:saltHeaders(SALT_CONFIG.base+'/')});if(!r.ok)return null;const html=await r.text();const title=html.match(/<h1\b[^>]*>([\s\S]*?)<\/h1>/i);
  if(title&&!target.titles.some(t=>saltNorm(t)&&saltNorm(t)===saltNorm(title[1])))return null;
  return {url:r.url||hits[0].url,html,slug:hits[0].slug};
}
function saltEpisode(catalog,target,season) {
  if(!target.tv)return {url:catalog.url,sourceSeason:0,sourceEpisode:0};
  const links=saltPageLinks(catalog.html,catalog.url,'episode').map(v=>{const m=v.slug.match(/^(.*)-(\d+)x(\d+)$/);return m&&saltNorm(m[1])===saltNorm(catalog.slug)?{url:v.url,sourceSeason:Number(m[2]),sourceEpisode:Number(m[3])}:null;}).filter(Boolean);
  const unique=[];for(const l of links)if(!unique.some(v=>v.sourceSeason===l.sourceSeason&&v.sourceEpisode===l.sourceEpisode))unique.push(l);
  if(target.absolute){const matches=unique.filter(v=>v.sourceEpisode===target.absolute);return matches.length===1?matches[0]:null;}
  // Site numbering can be absolute inside a TMDB-like season (One Piece S2 starts at 62).
  const matches=unique.filter(v=>v.sourceSeason===season).sort((a,b)=>a.sourceEpisode-b.sourceEpisode);
  return matches[target.relativeEpisode-1]||null;
}
function saltEmbeds(html,page) {
  const out=[];
  for(const m of html.matchAll(/multi-lang-plyr\.php\?data=([^"'\s<>]+)/gi)){try{const items=JSON.parse(saltBase64(decodeURIComponent(saltText(m[1]).split('&')[0])));for(const item of items){const url=saltUrl(item.link,page);if(url)out.push({url,language:SALT_LANGUAGE[String(item.language||'').toLowerCase()]||'und',label:'Abyss · '+(item.language||'Unknown audio')});}}catch(_) {}}
  for(const m of html.matchAll(/<button\b([^>]*)>([\s\S]*?)<\/button>/gi)){const src=m[1].match(/data-src\s*=\s*["']([^"']+)/i);if(src){const url=saltUrl(src[1],page);if(url)out.push({url,language:'und',label:saltText(m[2])||'Server'});}}
  for(const m of html.matchAll(/<iframe\b[^>]*\b(?:data-src|src)\s*=\s*["']([^"']+)/gi)){const url=saltUrl(m[1],page);if(url&&!url.includes('multi-lang-plyr.php'))out.push({url,language:'und',label:new URL(url).hostname.includes('as-cdn')?'MyStream':new URL(url).hostname});}
  return out.filter((v,i,list)=>list.findIndex(x=>x.url===v.url)===i);
}
async function saltMedia(url,headers) {
  try {
    if(/\.mp4(?:[?#]|$)/i.test(url)){const r=await saltFetch(url,{method:'HEAD',headers});return r.ok&&/^video\//i.test(r.headers.get('content-type')||'')?{url:r.url||url,type:'mp4',quality:'Auto'}:null;}
    const r=await saltFetch(url,{headers});if(!r.ok)return null;const mime=r.headers.get('content-type')||'';
    if(/^video\//i.test(mime))return {url:r.url||url,type:'mp4',quality:'Auto'};
    const body=(await r.text()).trim();
    if(body.startsWith('#EXTM3U'))return {url:r.url||url,type:'m3u8',quality:'Auto'};
    if(/<MPD\b/.test(body)&&!/<ContentProtection\b/.test(body))return {url:r.url||url,type:'mpd',quality:'Auto'};
  }catch(_) {}return null;
}
async function saltResolve(embed,target,page,depth,seen) {
  if(depth>3||seen[embed.url])return [];seen[embed.url]=true;
  const host=new URL(embed.url).hostname;
  // Abyss's public player requires its service worker and a custom encrypted byte transport.
  // Its /sora and #mp4/#hls URLs are not direct native media; returning them would be false.
  if(/(?:^|\.)(?:abyssplayer\.com|abyss\.to)$/.test(host))return [];
  const headers=saltHeaders(page);
  if(/\.(?:m3u8|mpd|mp4)(?:[?#]|$)/i.test(embed.url)){const direct=await saltMedia(embed.url,headers);return direct?[Object.assign(direct,{language:embed.language,label:embed.label,headers,subtitles:[]})]:[];}
  try {
    const r=await saltFetch(embed.url,{headers});if(!r.ok)return [];const actual=r.url||embed.url,html=await r.text();if(!saltHostIdentity(html,target))return [];
    if(/filesforever\.link$/.test(host)){
      const sid=html.match(/const sid\s*=\s*["']([^"']+)/);if(!sid)return [];
      const endpoint=new URL('/embedhelper2.php',actual).toString();
      const api=await saltFetch(endpoint,{method:'POST',headers:Object.assign(saltHeaders(actual),{'Content-Type':'application/x-www-form-urlencoded'}),body:'sid='+encodeURIComponent(sid[1])+'&UserFavSite=&currentDomain='+encodeURIComponent(JSON.stringify([new URL(page).hostname,new URL(actual).hostname]))});
      if(!api.ok)return [];const j=await api.json();if(j.domain_blocked||!j.sources||!j.mresult)return [];
      const ids=JSON.parse(saltBase64(j.mresult)),results=[];
      for(const key of Object.keys(j.sources)){const src=j.sources[key];if(!ids[key]||!src.siteUrl)continue;const url=saltUrl(src.siteUrl+ids[key]+(src.embed_suffix||''),actual);if(url)results.push(...await saltResolve({url,language:embed.language,label:embed.label+' / '+(src.friendlyName||key)},target,actual,depth+1,seen));}return results;
    }
    const myStream=await saltMyStream(html,actual,page,target,embed);if(myStream!==null)return myStream;
    let content=saltUnpack(html);
    // Byse's public details identify the upload independently of the source site's label.
    if(/byse[^.]*\.com$/.test(new URL(actual).hostname)&&/\/e\/([^/]+)$/.test(new URL(actual).pathname)){
      const code=new URL(actual).pathname.split('/').pop(),detail=await saltFetch(new URL('/api/videos/'+code+'/embed/details',actual).toString(),{headers:saltHeaders(actual)});
      if(!detail.ok)return [];const j=await detail.json();if(j.title&&!saltHostIdentity('<title>'+j.title+'</title>',target))return [];
      if(j.embed_frame_url)return saltResolve({url:saltUrl(j.embed_frame_url,actual),language:embed.language,label:embed.label},target,actual,depth+1,seen);
    }
    const sourceUrls=[];
    for(const m of content.matchAll(/(?:file|src|hls|hlsUrl|video_url)\s*[:=]\s*["']((?:\\.|[^"'\\])+)["']/gi)){const url=saltUrl(saltLiteral(m[1]),actual);if(url&&/\.(?:m3u8|mp4|mpd)(?:[?#]|$)/i.test(url)&&!sourceUrls.includes(url))sourceUrls.push(url);}
    for(const m of content.matchAll(/<source\b[^>]*\bsrc\s*=\s*["']([^"']+)/gi)){const url=saltUrl(m[1],actual);if(url&&!sourceUrls.includes(url))sourceUrls.push(url);}
    const mediaHeaders=saltHeaders(actual),subtitles=[];
    for(const m of content.matchAll(/file\s*:\s*["']([^"']+\.vtt(?:\?[^"']*)?)["'][\s\S]{0,180}?label\s*:\s*["']([^"']+)/gi)){const url=saltUrl(m[1],actual);if(url)subtitles.push({url,name:saltText(m[2]),language:SALT_LANGUAGE[m[2].toLowerCase()]||'und',headers:mediaHeaders});}
    const results=[];for(const url of sourceUrls){const media=await saltMedia(url,mediaHeaders);if(media)results.push(Object.assign(media,{language:embed.language,label:embed.label,headers:mediaHeaders,subtitles}));}
    if(!results.length){for(const m of html.matchAll(/<iframe\b[^>]*\bsrc\s*=\s*["']([^"']+)/gi)){const url=saltUrl(m[1],actual);if(url)results.push(...await saltResolve({url,language:embed.language,label:embed.label},target,actual,depth+1,seen));}}
    return results;
  }catch(_) {return [];}
}
async function getStreams(id,mediaType='tv',season=1,episode=1) {
  try {const target=await saltMetadata(id,mediaType,episode);if(!target)return [];const catalog=await saltCatalog(target);if(!catalog)return [];const ep=saltEpisode(catalog,target,Math.max(1,parseInt(season,10)||1));if(!ep)return [];Object.assign(target,ep);const r=await saltFetch(ep.url,{headers:saltHeaders(catalog.url)});if(!r.ok)return [];const actual=r.url||ep.url;if(new URL(actual).pathname.replace(/\/$/,'')!==new URL(ep.url).pathname.replace(/\/$/,''))return [];const html=await r.text();if(target.tv&&!saltPageLinks(html,actual,'series|anime').some(v=>saltNorm(v.slug)===saltNorm(catalog.slug)))return [];const embeds=saltEmbeds(html,actual),seen={},settled=await Promise.all(embeds.map(v=>saltResolve(v,target,actual,0,seen))),out=[];for(const src of settled.flat()){if(out.some(v=>v.url===src.url&&v.language===src.language))continue;const audio=src.language==='ja'?'SUB':src.language==='en'?'DUB':src.language==='und'?(/multi-audio/i.test(src.label)?'Multi-Audio':'Audio unspecified'):src.language.toUpperCase();out.push({name:SALT_CONFIG.name+' | '+src.label+' ['+audio+']',title:target.titles[0]+(target.tv?' · S'+ep.sourceSeason+' E'+ep.sourceEpisode:''),url:src.url,type:src.type,quality:src.quality,language:src.language,provider:SALT_CONFIG.name,headers:src.headers,subtitles:src.subtitles});}return out;
  }catch(error){console.error('['+SALT_CONFIG.name+'] '+(error&&error.message||error));return [];}
}
module.exports={getStreams};globalThis.getStreams=getStreams;

async function saltValidateHls(url,headers,initialBody) {
  try {
    let base=url;
    for(let depth=0;depth<4;depth++) {
      const response=depth===0&&initialBody?null:await saltFetch(base,{headers});if(response&&!response.ok)return false;
      const body=depth===0&&initialBody?initialBody:(await response.text()).trim();if(!body.startsWith('#EXTM3U'))return false;
      base=response&&response.url||base;const lines=body.split(/\r?\n/).map(l=>l.trim()),variants=[];
      for(let i=0;i<lines.length;i++)if(lines[i].startsWith('#EXT-X-STREAM-INF:')) {
        const uri=lines.slice(i+1).find(l=>l&&!l.startsWith('#'));
        const size=/RESOLUTION=(\d+)x(\d+)/.exec(lines[i]);
        if(uri)variants.push({uri,area:size?Number(size[1])*Number(size[2]):0});
      }
      if(variants.length){variants.sort((a,b)=>b.area-a.area);base=new URL(variants[0].uri,base).href;continue;}
      const uri=lines.find(l=>l&&!l.startsWith('#'));if(!uri)return false;
      const segment=await saltFetch(new URL(uri,base).href,{headers:Object.assign({},headers,{Range:'bytes=0-1023'})});
      return segment.ok&&!/text\/html|application\/json/i.test(segment.headers.get('content-type')||'');
    }
  }catch(_){}return false;
}
async function saltMyStream(html,actual,page,target,embed) {
  const content=saltUnpack(html);
  const match=content.match(/VPlayer\(\s*["']([a-z0-9]+)["']\s*,\s*(\{[\s\S]*?\})\s*,\s*(?:true|false)/i);
  if(!match)return null;
  let config;try{config=JSON.parse(match[2]);}catch(_){return [];}
  if(!config.title||!saltHostIdentity('<title>'+config.title+'</title>',target))return [];
  const endpoint=new URL('/player/index.php?data='+encodeURIComponent(match[1])+'&do=getVideo',actual).href;
  const headers=Object.assign(saltHeaders(actual),{'Content-Type':'application/x-www-form-urlencoded','X-Requested-With':'XMLHttpRequest'});
  const r=await saltFetch(endpoint,{method:'POST',headers,body:'hash='+encodeURIComponent(match[1])+'&r='+encodeURIComponent(page)});
  if(!r.ok)return [];const data=await r.json(),url=saltUrl(data.videoSource,actual);if(!url)return [];
  const mediaHeaders=saltHeaders(actual),master=await saltFetch(url,{headers:mediaHeaders});if(!master.ok)return [];
  const masterBody=(await master.text()).trim();if(!masterBody.startsWith('#EXTM3U'))return [];
  const media={url:master.url||url,type:'m3u8',quality:'Auto'};
  if(!await saltValidateHls(media.url,mediaHeaders,masterBody))return [];
  const subtitles=[];
  for(const track of config.tracks||[])if(track.kind==='captions'&&track.file){
    const trackUrl=saltUrl(track.file,actual);if(!trackUrl)continue;
    const sr=await saltFetch(trackUrl,{headers:mediaHeaders});
    if(sr.ok&&(await sr.text()).trimStart().startsWith('WEBVTT'))subtitles.push({url:trackUrl,name:track.label||track.language||'Subtitles',language:SALT_LANGUAGE[String(track.label||'').toLowerCase()]||track.language||'und',headers:mediaHeaders});
  }
  let language=embed.language,label=embed.label;
  if(media.type==='m3u8'){
    const body=masterBody;
    const audio=[...body.matchAll(/#EXT-X-MEDIA:TYPE=AUDIO[^\n]*NAME="([^"]+)"/g)].map(m=>m[1]);
    if(audio.length>1){language='und';label='MyStream · Multi-Audio ('+audio.length+' tracks)';}
  }
  return [Object.assign(media,{language,label,headers:mediaHeaders,subtitles})];
}
