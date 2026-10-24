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

async function meguIdentity(id,type,season){
  const ctx=globalThis.MORROW_MEDIA_CONTEXT||{};
  if(type!=='movie'&&Number(season)>1&&!ctx.animeEpisode)return null;
  const known=Number(ctx.anilistId)||Number((String(id).match(/^anilist:(\d+)$/)||[])[1]);
  let title=ctx.animeTitle,year;
  if(!known&&!title){
    const raw=String(id).replace(/^tmdb:/,'');
    if(/^\d+$/.test(raw)||/^tt\d+$/.test(raw)){
      if(!globalThis.TMDB_API_KEY)return null;
      let data=await meguJson('https://api.themoviedb.org/3/'+(/^tt/.test(raw)?'find/'+raw:(type==='movie'?'movie/':'tv/')+raw)+'?api_key='+encodeURIComponent(globalThis.TMDB_API_KEY)+(/^tt/.test(raw)?'&external_source=imdb_id':''));
      if(/^tt/.test(raw)){const candidates=type==='movie'?data.movie_results:data.tv_results;if(!candidates||candidates.length!==1)return null;data=candidates[0];}
      title=data.name||data.title;year=Number(String(data.first_air_date||data.release_date||'').slice(0,4));
    }else if(!/^[a-z]+:/i.test(raw))title=raw;
  }
  const fields='id idMal format seasonYear title { english romaji native }';
  const payload=await meguJson('https://graphql.anilist.co',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(known?
    {query:'query($id:Int){Media(id:$id,type:ANIME){'+fields+'}}',variables:{id:known}}:
    {query:'query($search:String){Page(perPage:15){media(search:$search,type:ANIME){'+fields+'}}}',variables:{search:title}})});
  let items=known?[payload.data&&payload.data.Media]:payload.data&&payload.data.Page&&payload.data.Page.media||[];
  items=items.filter(Boolean).filter(a=>(type==='movie')===(a.format==='MOVIE'));
  if(!known){items=items.filter(a=>Object.values(a.title||{}).some(t=>meguNorm(t)===meguNorm(title)));if(items.length>1&&year)items=items.filter(a=>Number(a.seasonYear)===year);}
  return items.length===1?items[0]:null;
}

/* The site's own published native-server list and exact AniList episode routes. */
const MEGU_BASE='https://meguanime.com';
function meguNorm(s){return String(s||'').normalize('NFKD').replace(/[\u0300-\u036f]/g,'').toLowerCase().replace(/[^a-z0-9]+/g,' ').trim();}
async function meguFetch(url,options){const opts=Object.assign({},options,{timeoutMs:3500});opts.headers=Object.assign({Referer:MEGU_BASE+'/',Origin:MEGU_BASE},options&&options.headers);return fetch(url,opts);}
async function meguJson(url,options){const r=await meguFetch(url,options);if(!r.ok)throw new Error('MeguAnimes HTTP '+r.status);return r.json();}
function meguRoutes(identity,ep,lang){
 const q='al='+identity.id+'&ep='+ep+'&lang='+lang,t=encodeURIComponent(identity.title.english||identity.title.romaji||'');
 const paths=[['Kaa','kaa?'+q],['Anikoto','anikoto?'+q],['AniNeko','anineko?'+q+'&t='+t+'&all=1'],['AniWaves','aniwaves?'+q],['Vidnest','vidnest?'+q],['ANIMEGG','animegg?'+q],['Zoko','zoko?'+q],['AnimeSalt','animesalt?'+q+'&t='+t],['Kiwi','kiwi?'+q],['Miruro','miruro?'+q+'&all=1']];
 for(const p of ['light','l','raye','ryu'])paths.push(['AniLight '+p,'anilight?'+q+'&p='+p]);
 paths.push(['MegaPlay','stream?ani='+identity.id+'&ep='+ep+'&lang='+lang]);
 paths.push(['AllAnime','https://cdn.meguanime.com/aa?'+q+'&mal='+(identity.idMal||'')+'&title='+t]);
 if(lang==='sub')paths.push(['AniBD','anibd?al='+identity.id+'&ep='+ep]);
 if(lang==='dub')paths.push(['Dub','dub?al='+identity.id+'&ep='+ep]);
 return paths.map(([name,path])=>({name,url:path.startsWith('https://')?path:MEGU_BASE+'/api/'+path,lang}));
}
function meguPayloads(data,route){
 const entries=Array.isArray(data.providers)?data.providers:Array.isArray(data.servers)?data.servers:[data];
 return entries.filter(s=>s&&typeof s.source==='string'&&/^https:\/\//.test(s.source)).map(s=>Object.assign({},s,{server:s.name||s.server||s.id||route.name}));
}
async function meguResolve(route,identity,ep){try{
 const data=await meguJson(route.url),out=[];
 if(data.anilistId&&Number(data.anilistId)!==identity.id||data.episode&&Number(data.episode)!==ep)return [];
 for(const source of meguPayloads(data,route)){
  if(source.anilistId&&Number(source.anilistId)!==identity.id||source.episode&&Number(source.episode)!==ep)continue;
  const headers=Object.assign({Referer:MEGU_BASE+'/',Origin:MEGU_BASE},source.headers||{});delete headers.Range;
  const r=await meguFetch(source.source,{headers});if(!r.ok)continue;const body=(await r.text()).trim();if(!body.startsWith('#EXTM3U'))continue;
  if(!await morrowAccessibleHls(r.url||source.source,headers,body))continue;
  out.push({provider:'MeguAnimes',name:'MeguAnimes | '+route.name+(source.server!==route.name?' · '+source.server:'')+' ['+route.lang.toUpperCase()+']',title:(identity.title.english||identity.title.romaji)+' · Episode '+ep,url:r.url||source.source,type:'m3u8',quality:'Auto',language:route.lang==='dub'?'en':'ja',headers,subtitles:(source.tracks||[]).filter(t=>t.file&&(t.kind==='captions'||t.kind==='subtitles')).map(t=>({url:new URL(t.file,MEGU_BASE).href,language:t.srclang||t.lang||(/^English/i.test(t.label||'')?'en':'und'),name:t.label||'Subtitles',headers}))});
 }return out;
}catch(_){return [];}}
async function getStreams(id,type='tv',season=1,episode=1){try{
 const identity=await meguIdentity(id,type,season);if(!identity)return [];
 const ep=Number((globalThis.MORROW_MEDIA_CONTEXT||{}).animeEpisode||(type==='movie'?1:episode));if(!Number.isInteger(ep)||ep<1)return [];
 const info=await meguJson(MEGU_BASE+'/api/langinfo?al='+identity.id),langs=[];
 if(Number(info.subMax||info.sub)>=ep)langs.push('sub');
 if(Array.isArray(info.dubEps)?info.dubEps.some(n=>Number(n)===ep):Number(info.dubMax||info.dub)>=ep)langs.push('dub');
 const tasks=langs.flatMap(lang=>meguRoutes(identity,ep,lang));let at=0;const results=new Array(tasks.length);
 await Promise.all(Array.from({length:Math.min(6,tasks.length)},async()=>{while(at<tasks.length){const index=at++;results[index]=await meguResolve(tasks[index],identity,ep);}}));
 return results.flat().filter((s,i,a)=>a.findIndex(v=>v.url===s.url&&v.language===s.language)===i);
}catch(_){return [];}}
module.exports={getStreams};globalThis.getStreams=getStreams;
