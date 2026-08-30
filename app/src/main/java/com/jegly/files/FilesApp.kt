package com.jegly.files

import android.app.Application
import com.jegly.files.data.Archives
import com.jegly.files.data.Thumbnails
import java.io.File

class FilesApp : Application() {
    override fun onLowMemory() {
        super.onLowMemory()
        Thumbnails.evictAll()
        // Files unpacked from archives for previewing are pure cache — every one of them can be
        // re-extracted on demand, so they're the cheapest thing to give back under pressure.
        Archives.clearCache(this)
        // Vault previews are plaintext copies of encrypted files, so dropping them early is worth
        // doing for its own sake, not only to reclaim the space.
        runCatching { File(cacheDir, "vault-preview").deleteRecursively() }
    }
}
