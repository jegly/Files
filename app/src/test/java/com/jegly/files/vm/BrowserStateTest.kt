package com.jegly.files.vm

import com.jegly.files.ops.OpKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [BrowserState.ontoFilesystem], which is the fix for a bug that shipped three times.
 *
 * The ViewModel itself needs an Application and cannot be tested without Robolectric, which this
 * project deliberately does not pull in. BrowserState is plain data over java.io, so the part
 * carrying the invariant was moved onto it — the point being that the invariant is now checkable
 * rather than merely asserted in a comment.
 *
 * Note the explicit volumeRoot/currentDir in every case: their defaults call
 * Environment.getExternalStorageDirectory(), which is exactly the kind of android.* reference
 * that would drag this out of plain JUnit and into an emulator.
 */
class BrowserStateTest {

    private val root = File("/storage/emulated/0")
    private val dir = File("/storage/emulated/0/Download")

    private fun state(
        archive: ArchiveLocation? = null,
        vault: VaultLocation? = null,
        selection: Set<String> = emptySet(),
        query: String = "",
        clipboard: Clipboard? = null,
    ) = BrowserState(
        volumeRoot = root,
        currentDir = dir,
        archive = archive,
        vault = vault,
        selection = selection,
        query = query,
        clipboard = clipboard,
    )

    @Test
    fun `leaves a vault`() {
        val vaultRoot = File(dir, "MyVault")
        val before = state(vault = VaultLocation(vaultRoot, File(vaultRoot, "d")))
        assertTrue(before.inVault)

        val after = before.ontoFilesystem()

        // The bug: this field stayed set, and listing prefers it over the real directory, so
        // every caller that thought it had gone somewhere else re-listed the vault instead.
        assertNull(after.vault)
        assertFalse(after.inVault)
        assertFalse(after.inSyntheticTree)
    }

    @Test
    fun `leaves an archive`() {
        val before = state(archive = ArchiveLocation(File(dir, "backup.zip"), "photos"))
        assertTrue(before.inArchive)

        val after = before.ontoFilesystem()

        assertNull(after.archive)
        assertFalse(after.inSyntheticTree)
    }

    @Test
    fun `leaves both at once`() {
        val vaultRoot = File(dir, "MyVault")
        val before = state(
            archive = ArchiveLocation(File(dir, "backup.zip"), ""),
            vault = VaultLocation(vaultRoot, File(vaultRoot, "d")),
        )

        val after = before.ontoFilesystem()

        assertNull(after.archive)
        assertNull(after.vault)
    }

    @Test
    fun `drops selection and search, which belonged to where we were`() {
        val before = state(selection = setOf("/a", "/b"), query = "report")
        val after = before.ontoFilesystem()

        assertEquals(emptySet<String>(), after.selection)
        assertFalse(after.inSelectionMode)
        assertEquals("", after.query)
    }

    @Test
    fun `keeps the clipboard, so copy across volumes still works`() {
        val clip = Clipboard(OpKind.Copy, listOf(File(dir, "a.txt")))
        val after = state(clipboard = clip).ontoFilesystem()

        // Switching volumes goes through this, and copying from internal storage to an SD card
        // is the ordinary reason to do that. Clearing here would break it.
        assertEquals(clip, after.clipboard)
    }

    @Test
    fun `does not move you - the caller sets the destination`() {
        val after = state().ontoFilesystem()

        assertEquals(root, after.volumeRoot)
        assertEquals(dir, after.currentDir)
    }
}
