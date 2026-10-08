package com.nuvio.app.features.streams

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage

internal fun providerDisplayName(name: String): String =
    if (name.contains("Castle", ignoreCase = true)) "Castle" else name.trim()

internal fun providerLogoUrl(name: String): String? = providerLogos[
    providerDisplayName(name).lowercase().replace(Regex("[^a-z0-9]"), "")
]

private val providerLogos = mapOf(
    "miruro" to "https://static.everythingmoe.com/icons/miruro.png",
    "anikage" to "https://static.everythingmoe.com/icons/anicore.png",
    "hianime" to "https://static.everythingmoe.com/icons/hianime.png",
    "animepahe" to "https://static.everythingmoe.com/icons/animepahe.png",
    "animeheaven" to "https://static.everythingmoe.com/icons/animeheavenme.png",
    "reanime" to "https://static.everythingmoe.com/icons/reanime.png",
    "anikototv" to "https://static.everythingmoe.com/icons/animoe.png",
    "anikoto" to "https://static.everythingmoe.com/icons/animoe.png",
    "animenexus" to "https://static.everythingmoe.com/icons/animenexus.png",
    "lunarx" to "https://static.everythingmoe.com/icons/lunaranime.png",
    "animesalt" to "https://animesalt.cx/favicon.ico",
    "anidb" to "https://anidb.net/favicon.ico",
    "aniwaves" to "https://static.everythingmoe.com/icons/animetto2.png",
    "animestream" to "https://static.everythingmoe.com/icons/uniquestream.png",
    "kickassanime" to "https://static.everythingmoe.com/icons/kaa.png",
    "anistream" to "https://static.everythingmoe.com/icons/anistream.png",
    "animex" to "https://static.everythingmoe.com/icons/animexone.png",
    "kisskh" to "https://static.everythingmoe.com/icons/kisskh.png",
    "netmirror" to "https://net77.cc/favicon.ico",
    "moviebox" to "https://moviebox.ph/favicon.ico",
    "vidlink" to "https://vidlink.pro/favicon.ico",
    "purstream" to "https://purstream.co/favicon.ico"
)

@Composable
internal fun ProviderLogo(name: String, modifier: Modifier = Modifier, logoUrl: String? = null) {
    val url = logoUrl?.takeIf { it.isNotBlank() } ?: providerLogoUrl(name) ?: return
    AsyncImage(model = url, contentDescription = null,
        modifier = modifier.size(20.dp), contentScale = ContentScale.Fit)
}
