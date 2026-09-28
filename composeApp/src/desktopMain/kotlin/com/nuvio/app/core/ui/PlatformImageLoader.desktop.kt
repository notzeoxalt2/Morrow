package com.nuvio.app.core.ui

import coil3.ImageLoader
import coil3.disk.DiskCache
import okio.Path.Companion.toPath
import java.io.File

internal actual fun ImageLoader.Builder.configurePlatformImageLoader(): ImageLoader.Builder {
    val cacheDir = File(System.getProperty("user.home"), ".morrow/image_cache").apply { mkdirs() }
    return components {
        add(SkiaGifDecoder.Factory())
    }.diskCache {
        DiskCache.Builder()
            .directory(cacheDir.absolutePath.toPath())
            .maxSizeBytes(256L * 1024 * 1024)
            .build()
    }
}
