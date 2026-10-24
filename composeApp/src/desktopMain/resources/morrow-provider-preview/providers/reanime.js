// Decode the public FlixCloud player metadata and its base64/XOR HLS transport.
// Does not evaluate site scripts. The restricted byte routine supports the host's
// published three small i32 functions, allowing it to run in Morrow without WASM.
function fcLiteral(text, start) {
  let p=start;
  function ws(){while (/\s/.test(text[p]||'')&&p<text.length)p++;}
  function value(depth){
    if(depth>40)throw new Error('Metadata nesting');ws();const c=text[p];
    if(c==='"') {const begin=p++;let escaped=false;while(p<text.length){const x=text[p++];if(escaped)escaped=false;else if(x==='\\')escaped=true;else if(x==='"')return JSON.parse(text.slice(begin,p));}throw new Error('Metadata string');}
    if(c==='{'){p++;const o={};ws();while(text[p]!=='}'){let key;if(text[p]==='"')key=value(depth+1);else{const m=/^[A-Za-z_$][\w$]*/.exec(text.slice(p));if(!m)throw new Error('Metadata key');key=m[0];p+=key.length;}ws();if(text[p++]!==':')throw new Error('Metadata colon');if(key==='__proto__'||key==='constructor'||key==='prototype')throw new Error('Metadata key');o[key]=value(depth+1);ws();if(text[p]===','){p++;ws();}else if(text[p]!=='}')throw new Error('Metadata comma');}p++;return o;}
    if(c==='['){p++;const a=[];ws();while(text[p]!==']'){a.push(value(depth+1));ws();if(text[p]===','){p++;ws();}else if(text[p]!==']')throw new Error('Metadata array');}p++;return a;}
    const m=/^(?:true|false|null|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)/.exec(text.slice(p));if(!m)throw new Error('Metadata literal');p+=m[0].length;return JSON.parse(m[0]);
  }
  return value(0);
}
function fcBytes(s){const raw=atob(s),a=new Uint8Array(raw.length);for(let i=0;i<a.length;i++)a[i]=raw.charCodeAt(i);return a;}
function fcBase64(a){let s='';for(let i=0;i<a.length;i++)s+=String.fromCharCode(a[i]);return btoa(s);}
function fcRoutine(payload){
  const bytes=fcBytes(payload);if(bytes.length>4096||bytes[0]!==0||bytes[1]!==97||bytes[2]!==115||bytes[3]!==109)throw new Error('Unknown player routine');
  let p=8;const memory=new Uint8Array(65536),funcs=[],exports={};
  function leb(signed){let out=0,shift=0,b;do{if(shift>35||p>=bytes.length)throw new Error('Routine integer');b=bytes[p++];out|=(b&127)<<shift;shift+=7;}while(b&128);if(signed&&shift<32&&(b&64))out|=(-1)<<shift;return out;}
  function name(){const n=leb();let s='';for(let i=0;i<n;i++)s+=String.fromCharCode(bytes[p++]);return s;}
  while(p<bytes.length){const section=bytes[p++],length=leb(),end=p+length;if(end>bytes.length)throw new Error('Routine section');
    if(section===7){const count=leb();for(let i=0;i<count;i++){const n=name(),kind=bytes[p++],index=leb();if(kind===0)exports[n]=index;}}
    else if(section===10){const count=leb();if(count!==3)throw new Error('Routine functions');for(let i=0;i<count;i++){const bodyLength=leb(),bodyEnd=p+bodyLength,groups=leb();let locals=0;for(let j=0;j<groups;j++){locals+=leb();if(bytes[p++]!==127)throw new Error('Routine local type');}const code=[],blocks=[];while(p<bodyEnd){const op=bytes[p++],item={op};
      if([32,33,35,36,12,13].includes(op))item.a=leb();
      else if(op===65)item.a=leb(true);
      else if(op===45||op===58){item.a=leb();item.b=leb();}
      else if(op===2||op===3){if(bytes[p++]!==64)throw new Error('Routine block type');blocks.push(code.length);}
      else if(op===11){if(blocks.length){const begin=blocks.pop();code[begin].end=code.length;}}
      else if(![106,107,108,113,114,115,116,117,118,79].includes(op))throw new Error('Routine opcode '+op);
      code.push(item);
    }if(blocks.length)throw new Error('Routine blocks');funcs.push({locals,code});}}
    else if(section===11){const count=leb();for(let i=0;i<count;i++){if(leb()!==0||bytes[p++]!==65)throw new Error('Routine data');const offset=leb(true);if(bytes[p++]!==11)throw new Error('Routine data offset');const n=leb();if(offset<0||offset+n>memory.length)throw new Error('Routine memory');memory.set(bytes.slice(p,p+n),offset);p+=n;}}
    p=end;
  }
  let seed=0;
  function run(n,args){const f=funcs[exports[n]];if(!f)throw new Error('Missing routine export');const locals=args.concat(Array(f.locals).fill(0)),stack=[],control=[];let pc=0,steps=0;
    const pop=()=>{if(!stack.length)throw new Error('Routine stack');return stack.pop()|0;};
    while(pc<f.code.length){if(++steps>25000)throw new Error('Routine instruction limit');const x=f.code[pc];
      if(x.op===2||x.op===3)control.push({start:pc,end:x.end,loop:x.op===3});
      else if(x.op===11){if(control.length&&control[control.length-1].end===pc)control.pop();}
      else if(x.op===12||x.op===13){const branch=x.op===12||pop()!==0;if(branch){const idx=control.length-1-x.a,label=control[idx];if(!label)throw new Error('Routine branch');control.length=idx+(label.loop?1:0);pc=label.loop?label.start+1:label.end+1;continue;}}
      else if(x.op===32)stack.push(locals[x.a]);else if(x.op===33)locals[x.a]=pop();
      else if(x.op===35){if(x.a!==0)throw new Error('Routine global');stack.push(seed);}else if(x.op===36){if(x.a!==0)throw new Error('Routine global');seed=pop();}
      else if(x.op===65)stack.push(x.a);
      else if(x.op===45){const a=(pop()>>>0)+x.b;if(a>=memory.length)throw new Error('Routine load');stack.push(memory[a]);}
      else if(x.op===58){const v=pop(),a=(pop()>>>0)+x.b;if(a>=memory.length)throw new Error('Routine store');memory[a]=v&255;}
      else {const b=pop(),a=pop();if(x.op===106)stack.push((a+b)|0);else if(x.op===107)stack.push((a-b)|0);else if(x.op===108)stack.push(Math.imul(a,b));else if(x.op===113)stack.push(a&b);else if(x.op===114)stack.push(a|b);else if(x.op===115)stack.push(a^b);else if(x.op===116)stack.push(a<<(b&31));else if(x.op===117)stack.push(a>>(b&31));else if(x.op===118)stack.push(a>>>(b&31));else if(x.op===79)stack.push((a>>>0)>=(b>>>0)?1:0);}
      pc++;
    }return stack.length?pop():0;
  }
  return {mix(a,b,c,s){if(a.length!==32||b.length!==32||c.length!==32)throw new Error('Routine key size');memory.set(a,1000);memory.set(b,1032);memory.set(c,1064);run('_s',[s]);run('_r',[1000,1032,1064,1096,32]);return memory.slice(1096,1128);},playlistKey(){const offset=run('_c',[]);if(offset<0||offset+32>memory.length)throw new Error('Routine playlist key');return memory.slice(offset,offset+32);}};
}
async function fcSha(s){const a=new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(s)));return Array.from(a).map(b=>b.toString(16).padStart(2,'0')).join('');}
async function fcExtract(embed, referer){
  const origin=new URL(embed).origin;
  if(!/^https:\/\/flixcloud\.cc$/.test(origin))return null;
  const headers={Referer:referer||origin+'/',Origin:origin};
  const response=await fetch(embed,{headers});if(!response.ok)return null;const html=await response.text();
  const marker=/data:\s*\[null,null,\{type:"data",data:/.exec(html);if(!marker)return null;
  const data=fcLiteral(html,marker.index+marker[0].length);
  if(!data.obfuscation_seed||!data.obfuscated_crypto_data||!data.w_payload||data.isLive)return null;
  const seed=data.obfuscation_seed;let digest=seed;for(let i=0;i<3;i++)digest=await fcSha(digest+i);let second=digest;for(let i=0;i<3;i++)second=await fcSha(second+i);
  const obj=data.obfuscated_crypto_data['cd_'+digest.slice(24,32)]['ad_'+digest.slice(32,40)][0]['od_'+digest.slice(40,48)];
  const token=data[digest.slice(48,64)+'_'+digest.slice(56,64)];if(!token||!obj)return null;
  const tokenResponse=await fetch(origin+'/api/m3u8/'+encodeURIComponent(token),{headers:{Referer:embed,Origin:origin}});if(!tokenResponse.ok)return null;
  const tokens=await tokenResponse.json(),encrypted=tokens[(await fcSha(token+'vid')).slice(0,10)],fragment=tokens[(await fcSha(token+'key')).slice(0,10)];if(!encrypted||!fragment)return null;
  const routine=fcRoutine(data.w_payload),raw=routine.mix(fcBytes(obj['kf_'+digest.slice(8,16)]),fcBytes(data[second.slice(0,16)+'_'+second.slice(16,24)]),fcBytes(fragment),parseInt(seed.slice(0,8),16));
  const pb=await crypto.subtle.importKey('raw',raw,{name:'PBKDF2'},false,['deriveBits']);
  const derived=new Uint8Array(await crypto.subtle.deriveBits({name:'PBKDF2',salt:new TextEncoder().encode(seed),iterations:1000,hash:'SHA-256'},pb,256));
  for(let i=0;i<32;i++)derived[i]^=seed.charCodeAt(i%seed.length);
  const hashed=await crypto.subtle.digest('SHA-256',derived),key=await crypto.subtle.importKey('raw',hashed,{name:'AES-CBC'},false,['decrypt']);
  const url=new TextDecoder().decode(await crypto.subtle.decrypt({name:'AES-CBC',iv:fcBytes(obj['ivf_'+digest.slice(16,24)])},key,fcBytes(encrypted)));
  if(!/^https:\/\//.test(url)||!url.includes('/'+data.video_id+'/')||!url.includes('.m3u8'))return null;
  const xor=routine.playlistKey(),mediaHeaders={Referer:origin+'/',Origin:origin};
  const media=await fetch(url,{headers:mediaHeaders});if(!media.ok)return null;let playlist=await media.text();
  if(!playlist.trimStart().startsWith('#EXTM3U')){const bytes=fcBytes(playlist.trim());for(let i=0;i<bytes.length;i++)bytes[i]^=xor[i%32];playlist=new TextDecoder().decode(bytes);}
  if(!playlist.trimStart().startsWith('#EXTM3U'))return null;
  mediaHeaders['X-Morrow-Playlist-Xor']=fcBase64(xor);
  return {url,headers:mediaHeaders,subtitles:(data.subtitles||[]).filter(s=>s.url&&!/^(?:sup|pgs)$/i.test(s.format||'')).map(s=>({url:s.url,name:s.language||'Subtitle',language:/^english/i.test(s.language||'')?'en':'und',headers:{Referer:origin+'/'}})),audioType:data.audio_type,videoTitle:data.video_title,playlist};
}

// Genuine Re:Anime catalog and current FlixCloud hosts. Media context supplies anime-relative episodes.
const RE_ROOT='https://reanime.to';
function reNorm(s){return String(s||'').normalize('NFKD').replace(/[\u0300-\u036f]/g,'').toLowerCase().replace(/[^a-z0-9]+/g,' ').trim();}
async function reJson(url){const r=await fetch(url,{headers:{Referer:RE_ROOT+'/',Accept:'application/json'}});if(!r.ok)throw new Error('ReAnime HTTP '+r.status);return r.json();}
async function getStreams(id,mediaType='tv',season=1,episode=1){
  try{
    const context=globalThis.MORROW_MEDIA_CONTEXT||{};
    if(!context.anilistId&&Number(season)>1)return [];
    let aliases=[context.animeTitle].filter(Boolean);
    if(!aliases.length){const lookup=String(id).replace(/^tmdb:/,'');if(!/^\d+$/.test(lookup)||!globalThis.TMDB_API_KEY)return [];const meta=await reJson('https://api.themoviedb.org/3/'+(mediaType==='movie'?'movie':'tv')+'/'+lookup+'?api_key='+globalThis.TMDB_API_KEY);aliases=[meta.name,meta.title,meta.original_name,meta.original_title].filter(Boolean);}
    const number=mediaType==='movie'?1:Number(context.animeEpisode||episode||1);
    if(!Number.isInteger(number)||number<1)return [];
    const hits=new Map();
    for(const title of [...new Set(aliases)].slice(0,3)){
      const search=await reJson(RE_ROOT+'/api/v1/search?q='+encodeURIComponent(title)+'&limit=40&offset=0');
      for(const row of search.results||[]){
        const exactId=context.anilistId&&Number(row.anilist_id)===Number(context.anilistId);
        const exactTitle=Object.values(row.title||{}).some(t=>aliases.some(a=>reNorm(t)===reNorm(a)));
        if((context.anilistId?exactId:exactTitle)&&((mediaType==='movie')===String(row.format).toUpperCase().includes('MOVIE')))hits.set(row.anime_id,row);
      }
    }
    if(hits.size!==1)return [];
    const show=[...hits.values()][0],watch=RE_ROOT+'/watch/'+encodeURIComponent(show.anime_id)+'?ep='+number;
    const data=await reJson(RE_ROOT+'/api/v1/watch/'+encodeURIComponent(show.anime_id)+'?ep='+number);
    if(!data.anime||data.anime.anime_id!==show.anime_id||!Number(show.anilist_id))return [];
    const catalog=await reJson(RE_ROOT+'/api/v1/anime/'+encodeURIComponent(show.anime_id)+'/episodes?number='+number+'&limit=1');
    const selected=(catalog.data||[]).filter(e=>Number(e.episode_number)===number);
    if(selected.length!==1||selected[0].playable===false)return [];
    const options=await reJson(RE_ROOT+'/api/flix/'+show.anilist_id+'/'+number);
    if(!options.success)return [];
    const servers=new Map();
    for(const item of options.servers||[])if(item.dataLink&&item.serverName)servers.set(item.serverName+'|'+item.dataLink,item);
    const streams=await Promise.all([...servers.values()].map(async server=>{
      try{
        const source=await fcExtract(server.dataLink,watch);if(!source)return null;
        const hostTitle = reNorm(source.videoTitle);
        const titleAliases = Object.values(show.title || {}).concat(aliases).filter(Boolean).map(reNorm);
        const matchedAlias = titleAliases.find(alias => hostTitle.startsWith(alias + ' '));
        if (!matchedAlias) return null;
        const hostEpisode = hostTitle.slice(matchedAlias.length).match(/(?:^|\s)(?:episode\s*|ep\s*|e\s*)?0*(\d+)(?:\s|$)/);
        if (mediaType !== 'movie' && (!hostEpisode || Number(hostEpisode[1]) !== number)) return null;
        const multi=source.audioType==='dual'||/#EXT-X-MEDIA:.*TYPE=AUDIO/.test(source.playlist);
        // One published dual-audio manifest represents both site SUB and DUB options.
        // Keep its native tracks instead of calling the Japanese default an English dub.
        const language=multi?'und':server.dataType==='dub'?'en':'ja';
        return {provider:'ReAnime',name:'ReAnime | '+server.serverName+' ['+(multi?'Multi audio':server.dataType==='dub'?'DUB':'SUB')+']',title:(show.title.english||show.title.romaji||aliases[0])+' • Episode '+number+' • '+server.serverName,url:source.url,type:'m3u8',quality:'Auto',language,headers:source.headers,subtitles:source.subtitles};
      }catch(_){return null;}
    }));
    return streams.filter(Boolean);
  }catch(_){return [];}
}
globalThis.getStreams=getStreams;
module.exports={getStreams};
