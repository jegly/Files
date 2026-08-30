package com.jegly.files.model

enum class SortBy { Name, Size, Modified, Type }

data class SortSpec(
    val by: SortBy = SortBy.Name,
    val ascending: Boolean = true,
    /** Directories always float to the top, independent of the sort key. */
    val foldersFirst: Boolean = true,
) {
    fun comparator(): Comparator<FileEntry> {
        val key: Comparator<FileEntry> = when (by) {
            SortBy.Name -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            SortBy.Size -> compareBy { it.size }
            SortBy.Modified -> compareBy { it.lastModified }
            SortBy.Type -> compareBy<FileEntry> { it.extension }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        }
        val directional = if (ascending) key else key.reversed()
        return if (foldersFirst) {
            compareByDescending<FileEntry> { it.isDirectory }.then(directional)
        } else {
            directional
        }
    }
}

enum class ViewMode { List, Grid }
