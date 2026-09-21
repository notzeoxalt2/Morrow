# AnimeByMorrow

This package contains only the domains supplied in the request.
It does not include the older all-in-one GitHub providers.

Nuvio now shows each supplied domain as its own provider entry. Each provider
uses the same resilient multi-server implementation, so enabling one site does
not require enabling the others.

## Included Sites

- AnimeSalt: https://animesalt.cx/
- SaltAnime: https://saltanime.in/
- AnimeDekho: https://animedekho.app/
- HiAnime: https://hianime.at/home
- Anikage: https://anikage.cc/
- Miruro: https://www.miruro.to/
- Re:Anime: https://reanime.to/home
- AnimePahe: https://animepahe.pw/
- AnikotoTV: https://anikototv.to/
- Enma: https://www.enma.lol/
- Anime Nexus: https://anime.nexus/
- AniDB: https://anidb.app/home
- Anidap: https://anidap.lol/
- AnimeX: https://animex.one/home
- animeTVplus: https://animetvplus.xyz/
- Anistream: https://anistream.one/
- Kaa: https://kaa.lt/
- JustAnime: https://justanime.to/
- Aniwaves: https://aniwaves.ru/
- AnimeHeaven: https://animeheaven.me/
- Anitaku: https://anitaku.io/
- LunarX: https://lunarx.to/

## Streams

The provider requests every server returned for the selected episode, separately
for SUB and DUB. It keeps working servers when another server is down, and
preserves stream headers, subtitle tracks, and intro/outro chapters.

The public sites rotate domains and server implementations, so no provider can
guarantee that every third-party host is online at every moment. `site-status.json`
records the live homepage checks made while this package was built.

## Install

In Nuvio, open **Settings -> Local Scrapers** and add the raw URL of this package's
`manifest.json` after hosting the folder on a static file host.
