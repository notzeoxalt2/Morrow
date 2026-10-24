/* Anonymous browser transport published in AniFlix's public 5855 client chunk (Oct 22).
 * These are public client protocol constants, not a user's account/session credentials.
 */
const AF_API_BASE='https://api.aniflix.uno';
const AF_TRANSPORT_SEED='aniflix-browser-transport-v1';
const AF_PUBLIC_HMAC='8f1f51c3dcefb30bc3075e45327dce3942e52d344d597c48bd81a1d2603e1d5e';
function afHex(bytes){return Array.from(new Uint8Array(bytes),b=>b.toString(16).padStart(2,'0')).join('');}
async function afTransportKey(seed){const digest=await crypto.subtle.digest('SHA-256',new TextEncoder().encode(seed));return crypto.subtle.importKey('raw',digest,{name:'AES-GCM'},false,['encrypt','decrypt']);}
async function afApiHeaders(url,options){
 const timestamp=String(Date.now()),nonce=afHex(crypto.getRandomValues(new Uint8Array(16))),method=String(options&&options.method||'GET').toUpperCase(),u=new URL(url);
 const parts=[method,u.pathname+u.search,timestamp,nonce];
 if(options&&typeof options.body==='string'&&options.body)parts.push(afHex(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(options.body))));
 const hmac=await crypto.subtle.importKey('raw',new TextEncoder().encode(AF_PUBLIC_HMAC),{name:'HMAC',hash:{name:'SHA-256'}},false,['sign']);
 const signature=afHex(await crypto.subtle.sign('HMAC',hmac,new TextEncoder().encode(parts.join('|'))));
 const iv=crypto.getRandomValues(new Uint8Array(12)),payload=new TextEncoder().encode(JSON.stringify({ts:Number(timestamp),nonce:nonce.slice(0,8)}));
 const encrypted=new Uint8Array(await crypto.subtle.encrypt({name:'AES-GCM',iv},await afTransportKey(AF_TRANSPORT_SEED),payload));
 const combined=new Uint8Array(iv.length+encrypted.length);combined.set(iv);combined.set(encrypted,iv.length);
 let binary='';for(const b of combined)binary+=String.fromCharCode(b);
 return {'x-client-signature':signature,'x-client-timestamp':timestamp,'x-client-nonce':nonce,'x-aniflix-secret-token':btoa(binary).replace(/\+/g,'-').replace(/\//g,'_').replace(/=+$/,''),'x-accept-encrypted':'1'};
}
async function afApiPayload(value){
 if(!value||!value.ok||typeof value.enc!=='string')return value;
 let raw=value.enc.replace(/-/g,'+').replace(/_/g,'/');while(raw.length%4)raw+='=';
 const bytes=Uint8Array.from(atob(raw),c=>c.charCodeAt(0));if(bytes.length<29)throw Error('Invalid AniFlix envelope');
 const body=await crypto.subtle.decrypt({name:'AES-GCM',iv:bytes.subarray(0,12)},await afTransportKey(AF_TRANSPORT_SEED),bytes.subarray(12));
 return JSON.parse(new TextDecoder().decode(body));
}

/* Actual AniFlix episode bundle and server source endpoints. No Anidap aliasing. */
const AF_BASE='https://aniflix.uno';
const AF_UA='Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0 Safari/537.36';
function afNorm(s){return String(s||'').normalize('NFKD').replace(/[\u0300-\u036f]/g,'').toLowerCase().replace(/[^a-z0-9]+/g,' ').trim();}
function afUrl(s){try{const u=new URL(s,AF_API_BASE);return /^https?:$/.test(u.protocol)?u.href:null;}catch(_){return null;}}
async function afFetch(url,options){
  const defaults=[AF_BASE,AF_API_BASE].includes(new URL(url).origin)?{'User-Agent':AF_UA,Referer:AF_BASE+'/',Origin:AF_BASE}:{};
  const opts=Object.assign({},options,{headers:Object.assign(defaults,options&&options.headers)});
  opts.timeoutMs=10000;
  if(globalThis.SCRAPER_ID)return fetch(url,opts);
  const controller=typeof AbortController==='function'?new AbortController():null;
  if(controller)opts.signal=controller.signal;
  let timer;try{timer=setTimeout(()=>controller&&controller.abort(),15000);return await fetch(url,opts);}finally{if(timer)clearTimeout(timer);}
}
async function afJson(url,options){
 if(new URL(url).origin===AF_API_BASE)options=Object.assign({},options,{headers:Object.assign({},options&&options.headers,await afApiHeaders(url,options))});
 const r=await afFetch(url,options);if(!r.ok)throw new Error('AniFlix HTTP '+r.status);return afApiPayload(await r.json());
}
async function afIdentity(id,type,season){
  const ctx=globalThis.MORROW_MEDIA_CONTEXT||{};
  if(type!=='movie'&&Number(season)>1&&!ctx.animeEpisode)return null;
  const known=Number(ctx.anilistId)||Number((String(id).match(/^anilist:(\d+)$/)||[])[1]);
  let title=ctx.animeTitle,year;
  if(!known&&!title){
    const raw=String(id).replace(/^tmdb:/,'');
    if(/^\d+$/.test(raw)||/^tt\d+$/.test(raw)){
      if(!globalThis.TMDB_API_KEY)return null;
      let data=await afJson('https://api.themoviedb.org/3/'+(/^tt/.test(raw)?'find/'+raw:(type==='movie'?'movie/':'tv/')+raw)+'?api_key='+encodeURIComponent(globalThis.TMDB_API_KEY)+(/^tt/.test(raw)?'&external_source=imdb_id':''));
      if(/^tt/.test(raw)){const candidates=type==='movie'?data.movie_results:data.tv_results;if(!candidates||candidates.length!==1)return null;data=candidates[0];}
      title=data.name||data.title;year=Number(String(data.first_air_date||data.release_date||'').slice(0,4));
    }else if(!/^[a-z]+:/i.test(raw))title=raw;
  }
  const fields='id idMal format seasonYear title { english romaji native }';
  const payload=await afJson('https://graphql.anilist.co',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(known?
    {query:'query($id:Int){Media(id:$id,type:ANIME){'+fields+'}}',variables:{id:known}}:
    {query:'query($search:String){Page(perPage:15){media(search:$search,type:ANIME){'+fields+'}}}',variables:{search:title}})});
  let items=known?[payload.data&&payload.data.Media]:payload.data&&payload.data.Page&&payload.data.Page.media||[];
  items=items.filter(Boolean).filter(a=>(type==='movie')===(a.format==='MOVIE'));
  if(!known){items=items.filter(a=>Object.values(a.title||{}).some(t=>afNorm(t)===afNorm(title)));if(items.length>1&&year)items=items.filter(a=>Number(a.seasonYear)===year);}
  return items.length===1?items[0]:null;
}
async function afMapLimit(items,limit,fn){const out=new Array(items.length);let i=0;await Promise.all(Array.from({length:Math.min(limit,items.length)},async()=>{while(i<items.length){const at=i++;try{out[at]=await fn(items[at]);}catch(_){out[at]=[];}}}));return out.flat();}
async function afServer(server,lang,identity,ep){
  const endpoint=afUrl(server.sources_api||server.link);
  if(!endpoint||![AF_BASE,AF_API_BASE].includes(new URL(endpoint).origin)||!new URL(endpoint).pathname.startsWith('/api/anime/sources'))return [];
  const data=await afJson(endpoint),category=lang==='eng'?'dub':lang==='jap'?'sub':null;
  if(data.available===false||(category&&data.category&&data.category!==category)||(data.episodeId&&server.did&&data.episodeId!==server.did))return [];
  const sources=Array.isArray(data.sources)?data.sources:(data.sources&&data.sources[lang]||[]),out=[],qualities=new Set();
  for(const source of sources){
    if(source.isPlayable===false)continue;
    const quality=source.quality||'Auto';if(qualities.has(quality))continue;
    const url=afUrl(source.url||source.streamUrl||source.file);if(!url)continue;
    const headers=Object.assign({'User-Agent':AF_UA,Referer:AF_BASE+'/'},new URL(url).origin===AF_API_BASE?{}:source.headers||{});
    if(![AF_BASE,AF_API_BASE].includes(new URL(url).origin)&&source.referer)headers.Referer=source.referer;
    delete headers.Range;
    const r=await afFetch(url,{headers});if(!r.ok||/text\/html/i.test(r.headers.get('content-type')||''))continue;
    const body=(await r.text()).trim();if(!body.startsWith('#EXTM3U')||!await morrowAccessibleHls(r.url||url,headers,body))continue;
    const language={eng:'en',jap:'ja',hindi:'hi'}[lang]||'und';
    const subtitles=(Array.isArray(data.subtitles)?data.subtitles:[]).map(t=>({url:afUrl(t.src||t.url||t.file),language:t.srclang||t.language||t.lang||'und',name:t.label||t.name||t.language||'Subtitles',headers})).filter(t=>t.url);
    qualities.add(quality);
    out.push({name:'AniFlix | '+(server.server_alias||server.server_name||source.server||'Server')+' ['+(language==='ja'?'SUB':language==='en'?'DUB':language.toUpperCase())+']',title:(identity.title.english||identity.title.romaji)+' · Episode '+ep,url:r.url||url,type:'m3u8',quality,language,provider:'AniFlix',headers,subtitles});
  }
  return out;
}
async function getStreams(id,type='tv',season=1,episode=1){
  try{
    const identity=await afIdentity(id,type,season);if(!identity)return [];
    const ep=Number((globalThis.MORROW_MEDIA_CONTEXT||{}).animeEpisode||(type==='movie'?1:episode));if(!Number.isInteger(ep)||ep<1)return [];
    const title=identity.title.english||identity.title.romaji;
    // Match the site's cached 100-episode bundle. One-off limit=1 requests were slower in Morrow.
    const bundle=await afJson(AF_API_BASE+'/api/anime/episodes?anilistId='+identity.id+'&animeTitle='+encodeURIComponent(title)+(identity.idMal?'&malId='+identity.idMal:'')+'&ep='+ep+'&offset='+Math.floor((ep-1)/100)*100+'&limit=100');
    if(Number(bundle.anime&&bundle.anime.anilist_id)!==identity.id)return [];
    const rows=(bundle.anime.episodes||[]).filter(e=>Number(e.ep_no)===ep);if(rows.length!==1)return [];
    const servers=[];for(const lang of ['jap','eng','hindi'])for(const server of rows[0].servers&&rows[0].servers[lang]||[])servers.push({server,lang});
    const streams=await afMapLimit(servers,4,v=>afServer(v.server,v.lang,identity,ep));
    return streams.filter((s,i,a)=>a.findIndex(v=>v.url===s.url&&v.language===s.language)===i);
  }catch(error){console.error('[AniFlix] '+String(error&&error.message||error));return [];}
}
module.exports={getStreams};globalThis.getStreams=getStreams;

// Validate the actual first media route, not just an accessible master playlist.
async function morrowMediaFetch(url, options) {
  if(globalThis.SCRAPER_ID)return fetch(url,options);
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
