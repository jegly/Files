package com.jegly.files.ops

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.jegly.files.model.FileEntry
import com.jegly.files.model.FileKind
import java.io.File

/**
 * Hands a file off to whatever app the user picks.
 *
 * Always through FileProvider and always through the system chooser. Two consequences worth
 * keeping:
 *  - No file:// Uri ever leaves this process, so there is no FileUriExposedException and the
 *    receiving app gets a scoped, revocable grant instead of a raw path.
 *  - Resolving openers via the chooser is why the manifest can omit QUERY_ALL_PACKAGES. We never
 *    ask the system what can handle a type; we let the system ask the user.
 *
 * APKs go through the same path deliberately. Without REQUEST_INSTALL_PACKAGES the installer
 * cannot be driven from here, so tapping an APK surfaces a chooser rather than silently starting
 * an install — which is the behaviour the manifest comment promises.
 */
object Opener {

    /** Result of an attempt to hand a file off; [error] is null when the chooser was shown. */
    fun open(context: Context, entry: FileEntry): String? {
        if (entry.isDirectory) return "That's a folder"
        val uri = runCatching { uriFor(context, entry.file) }
            .getOrElse { return "Can't share ${entry.name}: ${it.message}" }

        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, viewMimeType(entry))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

        return launchChooser(context, intent, "Open with")
    }

    fun share(context: Context, files: List<File>): String? {
        if (files.isEmpty()) return null
        val uris = runCatching { ArrayList(files.map { uriFor(context, it) }) }
            .getOrElse { return "Can't share: ${it.message}" }

        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND)
                .setType(shareMimeType(files))
                .putExtra(Intent.EXTRA_STREAM, uris.first())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE)
                .setType(shareMimeType(files))
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        /*
         * Android 17 stops implicitly granting URI permissions for ACTION_SEND and
         * ACTION_SEND_MULTIPLE. FLAG_GRANT_READ_URI_PERMISSION only ever covered the Intent's
         * data and clipData — never its extras — so up to now EXTRA_STREAM worked purely on the
         * strength of that implicit grant. Mirroring the URIs into clipData puts them somewhere
         * the flag genuinely applies, which is what keeps sharing working on 17 while remaining
         * correct on earlier releases.
         */
        intent.clipData = ClipData.newUri(context.contentResolver, "files", uris.first()).apply {
            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
        }

        return launchChooser(context, intent, "Share")
    }

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.provider", file)

    private fun launchChooser(context: Context, intent: Intent, title: String): String? = try {
        val chooser = Intent.createChooser(intent, title)

        /*
         * Launch from the real Activity where possible.
         *
         * LocalContext in Compose is a ContextThemeWrapper, not the Activity, so a naive
         * `context is Activity` check fails and every chooser ends up with
         * FLAG_ACTIVITY_NEW_TASK. That flag puts the chooser in its own task, which means Back
         * from whichever app the user picked drops them at the launcher instead of returning
         * here. Unwrapping to the Activity keeps the chooser in our task, and it is also the
         * launch path Android 17's background-activity-launch hardening expects.
         */
        val activity = context.findActivity()
        if (activity == null) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        (activity ?: context).startActivity(chooser)
        null
    } catch (e: ActivityNotFoundException) {
        "Nothing on this device can open that"
    } catch (t: Throwable) {
        t.message ?: "Couldn't open that"
    }

    /**
     * MimeTypeMap has no entry for source files, so `.kt` and friends resolve to
     * application/octet-stream and no text editor matches the chooser. Anything we already
     * classified as text is offered as text/plain instead.
     */
    private fun viewMimeType(entry: FileEntry): String = when {
        entry.kind == FileKind.Text && entry.mimeType == FileEntry.MIME_UNKNOWN -> "text/plain"
        else -> entry.mimeType
    }

    private fun Context.findActivity(): Activity? =
        generateSequence(this as Context?) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<Activity>()
            .firstOrNull()

    /**
     * Narrowest type that covers the whole batch: the exact type if every file shares one,
     * else the shared top-level category as a wildcard (e.g. "image/star"), else fully generic.
     */
    private fun shareMimeType(files: List<File>): String {
        val types = files.map { viewMimeType(FileEntry.from(it)) }.toSet()
        types.singleOrNull()?.let { return it }
        val categories = types.map { it.substringBefore('/') }.toSet()
        return categories.singleOrNull()?.let { "$it/*" } ?: "*/*"
    }
}
