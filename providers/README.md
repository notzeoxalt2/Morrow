# Morrow Anime Resolver

This package exposes **one** provider: the Anidap shared anime resolver. It searches the resolver's anime catalog, requests the server list for the selected episode, and keeps distinct returned server/audio variants.

Earlier versions showed the same resolver response under many site names (AnimeSalt, HiAnime, Miruro, AnimeDekho, and others). Those wrappers did not query those sites independently, so their links and server lists could repeat. They have been removed from this manifest. A site should be listed separately only after it has its own catalog, episode, and source adapter.

The resolver can return links from multiple server labels, but a responsive media URL is not proof that playback works. Site availability and actual player decoding still depend on the remote host, headers, and current episode data.

## Install

Host this folder as a static file package and add its `manifest.json` URL in Morrow's provider settings.
