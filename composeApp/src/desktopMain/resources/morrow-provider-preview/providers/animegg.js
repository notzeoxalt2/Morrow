/* Genuine ANIMEGG catalog, explicit episode links and named site servers. */
const EGG_ROOT='https://www.animegg.org';
function eggText(s){return String(s||'').replace(/<[^>]*>/g,' ').replace(/&amp;/g,'&').replace(/&quot;/g,'"').replace(/&#0?39;|&apos;/g,"'").replace(/&nbsp;/g,' ').replace(/\s+/g,' ').trim();}
function eggNorm(s){return eggText(s).toLowerCase().replace(/[^a-z0-9]/g,'');}
function eggAttr(tag,key){const m=tag.match(new RegExp('\\b'+key+'=["\x27]([^"\x27]*)','i'));return m?eggText(m[1]):'';}
async function eggPage(url,referer){const r=await fetch(url,{headers:{Referer:referer||EGG_ROOT+'/'}});if(!r.ok)throw new Error('ANIMEGG HTTP '+r.status);return r.text();}
function eggCards(html,titles){const found=[];for(const m of html.matchAll(/<a\b([^>]*\bclass=["']mse["'][^>]*)>([\s\S]*?)<\/a>/gi)){
 const title=eggText((m[2].match(/<h2\b[^>]*>([\s\S]*?)<\/h2>/i)||[])[1]);
 const href=eggAttr(m[1],'href');if(!/^\/series\/[a-z0-9-]+\/?$/i.test(href)||!titles.some(t=>eggNorm(t)===eggNorm(title)))continue;
 if(!found.some(v=>v.href===href))found.push({title,href});
}return found;}
function eggEpisode(html,number){const paths=new Set();for(const m of html.matchAll(/<a\b[^>]*>/gi)){
 const href=eggAttr(m[0],'href');if(!/\banm_det_pop\b/.test(eggAttr(m[0],'class')))continue;
 if(Number((href.match(/-episode-(\d+)$/)||[])[1])===number)paths.add(href);
}return paths.size===1?[...paths][0]:null;}
function eggServers(html){const list=[];for(const m of html.matchAll(/<a\b[^>]*data-version=["'](?:subbed|dubbed)["'][^>]*>/gi)){
 const id=eggAttr(m[0],'data-id'),audio=eggAttr(m[0],'data-version'),name=eggAttr(m[0],'data-mirror');
 if(/^\d+$/.test(id)&&name&&!list.some(v=>v.id===id&&v.audio===audio))list.push({id,audio,name});
}return list;}
function eggSources(html,title,episode){
 const heading=eggAttr((html.match(/<meta\b[^>]*property=["']og:title["'][^>]*>/i)||[])[0]||'','content');
 if(eggNorm(heading)!==eggNorm(title+' Episode '+episode))return [];
 const section=(html.match(/var\s+videoSources\s*=\s*\[([\s\S]*?)\]\s*;/)||[])[1]||'';
 const list=[];for(const m of section.matchAll(/\{[^{}]*\bfile:\s*["']([^"']+)["'][^{}]*\}/g)){
 const path=eggText(m[1]);if(!/^\/play\/\d+\/video\.mp4\?/.test(path))continue;
 list.push({url:EGG_ROOT+path,quality:(m[0].match(/\blabel:\s*["']([^"']+)/)||[])[1]||'Auto'});
 }return list;
}
async function getStreams(id,type='tv',season=1,episode=1){try{
 // Movie format is not inferred from a TV search result.
 if(type==='movie')return [];
 const ctx=globalThis.MORROW_MEDIA_CONTEXT||{};if(Number(season)>1&&!ctx.animeEpisode)return [];
 let titles=[ctx.animeTitle].filter(Boolean);
 if(!titles.length&&globalThis.TMDB_API_KEY&&/^\d+$/.test(String(id).replace(/^tmdb:/,''))){const r=await fetch('https://api.themoviedb.org/3/tv/'+String(id).replace(/^tmdb:/,'')+'?api_key='+encodeURIComponent(globalThis.TMDB_API_KEY));if(r.ok){const j=await r.json();titles=[j.name,j.original_name].filter(Boolean);}}
 if(!titles.length&&!/^(?:tt\d+|[a-z]+:\d+|\d+)$/.test(String(id)))titles=[String(id)];
 const ep=Number(ctx.animeEpisode||episode);if(!titles.length||!Number.isInteger(ep)||ep<1)return [];
 const hits=[];for(const title of [...new Set(titles)].slice(0,2))for(const hit of eggCards(await eggPage(EGG_ROOT+'/search/?q='+encodeURIComponent(title)),titles))if(!hits.some(v=>v.href===hit.href))hits.push(hit);
 if(hits.length!==1)return [];const show=hits[0],page=EGG_ROOT+show.href,path=eggEpisode(await eggPage(page),ep);if(!path)return [];
 const watch=EGG_ROOT+path,servers=eggServers(await eggPage(watch,page));
 const groups=await Promise.all(servers.map(async server=>{try{
  const embed=EGG_ROOT+'/embed/'+server.id,html=await eggPage(embed,watch),out=[];
  for(const source of eggSources(html,show.title,ep)){
   const headers={Referer:embed};const probe=await fetch(source.url,{headers:{...headers,Range:'bytes=0-1023'}});
   if(!probe.ok||!/video\/mp4/i.test(probe.headers.get('content-type')||''))continue;
   out.push({provider:'ANIMEGG',name:'ANIMEGG | '+server.name+' ['+(server.audio==='dubbed'?'DUB':'SUB')+']',title:show.title+' · Episode '+ep,url:source.url,type:'mp4',quality:source.quality,language:server.audio==='dubbed'?'en':'ja',headers});
  }return out;
 }catch(_){return [];}}));return groups.flat();
}catch(_){return [];}}
globalThis.getStreams=getStreams;module.exports={getStreams};
