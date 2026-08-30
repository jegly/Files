package com.jegly.files.model

import android.content.Intent

/**
 * A request to choose something, parsed out of the launching Intent.
 *
 * Two distinct modes, mirroring DocumentsUI's own PickActivity:
 *
 *  - [Mode.File] serves ACTION_GET_CONTENT for other apps, returning a content:// grant.
 *  - [Mode.Destination] is internal: AOSP's "Copy to…"/"Move to…" launch their own PickActivity
 *    with ACTION_PICK_COPY_DESTINATION, let the user browse to a folder, and run the transfer on
 *    confirm (see DirectoryFragment.transferDocuments). No clipboard is involved.
 *
 * The Storage Access Framework intents — OPEN_DOCUMENT, CREATE_DOCUMENT, OPEN_DOCUMENT_TREE —
 * are routed by the system directly to DocumentsUI and cannot be claimed by a third-party
 * activity at all; the only way to appear inside those pickers is to publish a DocumentsProvider,
 * which is a different decision with different consequences for the app lock.
 */
data class PickRequest(
    val mimeTypes: List<String>,
    val allowMultiple: Boolean,
    val mode: Mode = Mode.File,
    /** Destination mode only: absolute paths being copied/moved. */
    val sources: List<String> = emptyList(),
    /** Destination mode only: true for a move, false for a copy. */
    val isMove: Boolean = false,
) {
    enum class Mode { File, Destination }

    /**
     * Directories always pass — they're how you reach the files, not candidates themselves.
     * In destination mode nothing but directories is selectable at all.
     */
    fun accepts(entry: FileEntry): Boolean = when (mode) {
        Mode.Destination -> entry.isDirectory
        Mode.File -> entry.isDirectory || mimeTypes.any { matches(it, entry.mimeType) }
    }

    private fun matches(filter: String, actual: String): Boolean = when {
        filter == "*/*" -> true
        filter.endsWith("/*") -> actual.substringBefore('/') == filter.substringBefore('/')
        else -> filter.equals(actual, ignoreCase = true)
    }

    companion object {
        /** Internal action, mirroring AOSP's Shared.ACTION_PICK_COPY_DESTINATION. */
        const val ACTION_PICK_COPY_DESTINATION = "com.jegly.files.PICK_COPY_DESTINATION"
        const val EXTRA_SOURCES = "com.jegly.files.SOURCES"
        const val EXTRA_IS_MOVE = "com.jegly.files.IS_MOVE"

        fun from(intent: Intent?): PickRequest? {
            intent ?: return null

            if (intent.action == ACTION_PICK_COPY_DESTINATION) {
                val sources = intent.getStringArrayListExtra(EXTRA_SOURCES).orEmpty()
                if (sources.isEmpty()) return null
                return PickRequest(
                    mimeTypes = listOf("*/*"),
                    allowMultiple = false,
                    mode = Mode.Destination,
                    sources = sources,
                    isMove = intent.getBooleanExtra(EXTRA_IS_MOVE, false),
                )
            }

            if (intent.action != Intent.ACTION_GET_CONTENT && intent.action != Intent.ACTION_PICK) {
                return null
            }
            // EXTRA_MIME_TYPES wins over the Intent's own type when both are present; that's the
            // contract callers rely on to request several unrelated types at once.
            val declared = intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toList()
            val single = intent.type?.takeIf { it.isNotBlank() }
            return PickRequest(
                mimeTypes = declared?.ifEmpty { null }
                    ?: listOfNotNull(single).ifEmpty { listOf("*/*") },
                allowMultiple = intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false),
            )
        }
    }
}
