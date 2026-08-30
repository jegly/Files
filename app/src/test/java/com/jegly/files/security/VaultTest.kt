package com.jegly.files.security

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * Vault behaviour, on a real filesystem. Like ArchiveCryptoTest this leans adversarial: the
 * interesting assertions are about what a vault refuses and what it does not leave lying around,
 * because those are the parts that fail silently if they regress.
 */
class VaultTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val password = "a decent vault password".toCharArray()

    private fun newVault(name: String = "MyVault"): Pair<File, ByteArray> {
        val dir = File(temp.root, name)
        Vault.create(dir, password)
        return dir to Vault.unlock(dir, password)
    }

    // Vault's transfer functions are suspend so they can report progress mid-file; these keep
    // the tests reading as straight-line code.
    private fun importFile(
        dir: File,
        source: File,
        name: String,
        key: ByteArray,
        onProgress: (Long) -> Unit = {},
    ) = runBlocking { Vault.importFile(dir, source, name, key) { onProgress(it) } }

    private fun exportFile(
        dir: File,
        storageName: String,
        dest: File,
        key: ByteArray,
        onProgress: (Long) -> Unit = {},
    ) = runBlocking { Vault.exportFile(dir, storageName, dest, key) { onProgress(it) } }

    private fun fileOf(name: String, body: String): File =
        File(temp.root, name).apply { parentFile?.mkdirs(); writeText(body) }

    // --- lifecycle --------------------------------------------------------------

    @Test
    fun `creates a vault that identifies itself`() {
        val (dir, _) = newVault()
        assertTrue(Vault.isVault(dir))
        assertFalse(Vault.isVault(temp.newFolder("ordinary")))
    }

    @Test
    fun `unlocks with the right password and refuses the wrong one`() {
        val (dir, key) = newVault()
        assertEquals(32, key.size)
        assertThrows(Vault.WrongPasswordException::class.java) {
            Vault.unlock(dir, "not the password".toCharArray())
        }
    }

    @Test
    fun `refuses a path that is a file rather than a folder`() {
        val notADir = File(temp.root, "notes.txt").apply { writeText("hello") }

        // Asserting the MESSAGE, not just the type. The old permissive check threw IOException
        // here too — listFiles() returns null for a file, which it read as "empty", so it sailed
        // past the guard and blew up further in trying to write a header inside a regular file.
        // Only the message distinguishes "refused for a reason we can explain" from "crashed
        // somewhere downstream", so a test asserting the type alone passes either way and proves
        // nothing. Watched fail against the old check before being kept.
        val e = assertThrows(IOException::class.java) { Vault.create(notADir, password) }
        assertTrue("was: ${e.message}", e.message.orEmpty().contains("isn't a folder"))
        assertEquals("hello", notADir.readText())
    }

    @Test
    fun `refuses a directory it cannot read rather than assuming it is empty`() {
        val unreadable = File(temp.root, "locked").apply { mkdirs() }
        File(unreadable, "secret.txt").writeText("pre-existing plaintext")
        // Running as root ignores the permission bit, so the case cannot be staged; skip rather
        // than assert something the platform was never going to do.
        assumeTrue(unreadable.setReadable(false) && unreadable.listFiles() == null)
        try {
            val e = assertThrows(IOException::class.java) { Vault.create(unreadable, password) }
            assertTrue("was: ${e.message}", e.message.orEmpty().contains("Can't read"))
            // The point of the guard: never build a vault around files it could not check for.
            assertFalse(File(unreadable, Vault.HEADER_NAME).exists())
        } finally {
            unreadable.setReadable(true)
        }
    }

    @Test
    fun `refuses to take over a non-empty directory`() {
        val dir = temp.newFolder("existing")
        File(dir, "already-here.txt").writeText("mine")
        assertThrows(IOException::class.java) { Vault.create(dir, password) }
    }

    /** The point of wrapping the master key rather than deriving from the password directly. */
    @Test
    fun `changing the password keeps the same master key and the same files`() {
        val (dir, key) = newVault()
        val root = Vault.treeRoot(dir)
        importFile(root, fileOf("in.txt", "still readable"), "in.txt", key)

        val replacement = "an entirely different password".toCharArray()
        Vault.changePassword(dir, password, replacement)

        assertThrows(Vault.WrongPasswordException::class.java) { Vault.unlock(dir, password) }
        val rekeyed = Vault.unlock(dir, replacement)
        assertArrayEquals(key, rekeyed)

        val out = File(temp.root, "out.txt")
        val entry = Vault.list(root, rekeyed).single()
        exportFile(root, entry.storageName, out, rekeyed)
        assertEquals("still readable", out.readText())
    }

    @Test
    fun `refuses a header whose iteration count has been lowered`() {
        val (dir, _) = newVault()
        val header = File(dir, Vault.HEADER_NAME)
        val bytes = header.readBytes()
        // Offsets: 8 magic + 4 version, then the iteration count.
        bytes[12] = 0; bytes[13] = 0; bytes[14] = 0; bytes[15] = 1
        header.writeBytes(bytes)
        assertThrows(Vault.WrongPasswordException::class.java) { Vault.unlock(dir, password) }
    }

    // --- contents and names -----------------------------------------------------

    @Test
    fun `round-trips a file through the vault`() {
        val (dir, key) = newVault()
        val root = Vault.treeRoot(dir)
        importFile(root, fileOf("notes.txt", "the actual contents"), "notes.txt", key)

        val entry = Vault.list(root, key).single()
        assertEquals("notes.txt", entry.name)
        assertFalse(entry.isDirectory)

        val out = File(temp.root, "recovered.txt")
        exportFile(root, entry.storageName, out, key)
        assertEquals("the actual contents", out.readText())
    }

    /**
     * The property the whole design exists for. Neither the contents nor the name may appear
     * anywhere under the vault directory, in any file, including the index.
     */
    @Test
    fun `neither contents nor file names appear anywhere on disk`() {
        val (dir, key) = newVault()
        importFile(
            Vault.treeRoot(dir),
            fileOf("payslip.txt", "salary 123456"),
            "payslip-2026-march.txt",
            key,
        )

        val everything = dir.walkTopDown().filter { it.isFile }.joinToString("") {
            it.readBytes().toString(Charsets.ISO_8859_1)
        }
        assertFalse("file name leaked", everything.contains("payslip"))
        assertFalse("contents leaked", everything.contains("salary"))
        assertFalse("contents leaked", everything.contains("123456"))
    }

    @Test
    fun `storage names are unrelated to real names`() {
        val (dir, key) = newVault()
        val root = Vault.treeRoot(dir)
        importFile(root, fileOf("a.txt", "a"), "the-same-name.txt", key)

        val other = File(temp.root, "OtherVault")
        Vault.create(other, password)
        val otherKey = Vault.unlock(other, password)
        val otherRoot = Vault.treeRoot(other)
        importFile(otherRoot, fileOf("b.txt", "a"), "the-same-name.txt", otherKey)

        // Identical name and identical contents in two vaults must not produce a shared
        // identifier that would let an observer correlate them.
        assertNotEquals(
            Vault.list(root, key).single().storageName,
            Vault.list(otherRoot, otherKey).single().storageName,
        )
    }

    @Test
    fun `a wrong key cannot list a vault directory`() {
        val (dir, _) = newVault()
        val root = Vault.treeRoot(dir)
        val wrong = ByteArray(32) { 7 }
        assertThrows(ArchiveCrypto.BadPasswordOrTamperedException::class.java) {
            Vault.list(root, wrong)
        }
    }

    // --- name safety ------------------------------------------------------------
    //
    // Defence in depth. Callers sanitise first and the index is authenticated, so none of these
    // is reachable today — the point is that the guarantee stops depending on every call site
    // remembering, since a stored name is later used to build a real path outside the vault.

    @Test
    fun `refuses a traversing name into the index`() {
        val (dir, key) = newVault()
        val root = Vault.treeRoot(dir)
        val source = fileOf("a.txt", "contents")

        // FileOperations.exportTree does File(destDir, entry.name) with no containment check, so
        // this record would write outside the folder the user picked -- with
        // MANAGE_EXTERNAL_STORAGE held. Same shape as Zip Slip.
        assertThrows(IllegalArgumentException::class.java) {
            importFile(root, source, "../../evil.txt", key)
        }
        assertTrue(Vault.list(root, key).isEmpty())
    }

    @Test
    fun `refuses separators, dot names and empties everywhere a name is stored`() {
        val (dir, key) = newVault()
        val root = Vault.treeRoot(dir)
        val entry = importFile(root, fileOf("a.txt", "x"), "ok.txt", key)

        for (bad in listOf("", "..", ".", "a/b", "../x", "nul\u0000byte")) {
            assertThrows("import accepted \"$bad\"", IllegalArgumentException::class.java) {
                importFile(root, fileOf("s.txt", "x"), bad, key)
            }
            assertThrows("createDirectory accepted \"$bad\"", IllegalArgumentException::class.java) {
                Vault.createDirectory(root, bad, key)
            }
            assertThrows("rename accepted \"$bad\"", IllegalArgumentException::class.java) {
                Vault.rename(root, entry.storageName, bad, key)
            }
        }

        // The one good entry is untouched by all that refusing.
        assertEquals(listOf("ok.txt"), Vault.list(root, key).map { it.name })
    }

    // --- orphan reclamation -----------------------------------------------------

    @Test
    fun `reclaims a blob no index refers to`() {
        val (dir, key) = newVault()
        val root = Vault.treeRoot(dir)
        val kept = importFile(root, fileOf("a.txt", "keep me"), "keep.txt", key)

        // What a kill between writing the blob and updating the index leaves behind.
        val orphan = File(root, "deadbeefdeadbeefdeadbeefdeadbeef")
        orphan.writeBytes(ByteArray(4096))
        orphan.setLastModified(System.currentTimeMillis() - 2 * 60 * 60 * 1000)

        val freed = Vault.reclaimOrphans(dir, key)

        assertEquals(4096L, freed)
        assertFalse(orphan.exists())
        assertTrue(File(root, kept.storageName).exists())
        assertEquals(listOf("keep.txt"), Vault.list(root, key).map { it.name })
    }

    @Test
    fun `does not reclaim a blob that is still within the grace period`() {
        val (dir, key) = newVault()
        val root = Vault.treeRoot(dir)

        // Stands in for an import in flight right now: on disk, not yet in the index. Deleting
        // this would be data loss, and is the one outcome reclamation must never produce.
        val inFlight = File(root, "beefbeefbeefbeefbeefbeefbeefbeef")
        inFlight.writeBytes(ByteArray(4096))

        assertEquals(0L, Vault.reclaimOrphans(dir, key))
        assertTrue(inFlight.exists())
    }

    @Test
    fun `reclaims inside subdirectories and leaves live entries alone`() {
        val (dir, key) = newVault()
        val root = Vault.treeRoot(dir)
        val photos = Vault.createDirectory(root, "Photos", key)
        val inner = importFile(photos, fileOf("p.txt", "inner"), "inside.txt", key)

        val orphan = File(photos, "cafecafecafecafecafecafecafecafe")
        orphan.writeBytes(ByteArray(1024))
        orphan.setLastModified(System.currentTimeMillis() - 2 * 60 * 60 * 1000)

        assertEquals(1024L, Vault.reclaimOrphans(dir, key))
        assertFalse(orphan.exists())
        assertEquals("inside.txt", Vault.list(photos, key).single().name)
        assertTrue(File(photos, inner.storageName).exists())
    }

    @Test
    fun `keeps a dangling index entry rather than deleting the name`() {
        val (dir, key) = newVault()
        val root = Vault.treeRoot(dir)
        val entry = importFile(root, fileOf("a.txt", "contents"), "gone.txt", key)

        // Blob removed from underneath the vault. Reclamation collects unreferenced bytes; it is
        // deliberately not in the business of removing a user's name for something.
        File(root, entry.storageName).delete()

        Vault.reclaimOrphans(dir, key)
        assertEquals(listOf("gone.txt"), Vault.list(root, key).map { it.name })
    }

    // --- identity binding -------------------------------------------------------
    //
    // Each of these was run against the code *before* the binding existed and observed to
    // succeed silently — swapping two blobs really did open one file as the other. They are
    // regression tests for a demonstrated hole, not hypotheticals. If one of them ever starts
    // passing for a different reason, disable blobContext/indexContext and watch it fail again.

    @Test
    fun `swapping two blobs makes neither open`() {
        val (dir, key) = newVault()
        val root = Vault.treeRoot(dir)
        val a = importFile(root, fileOf("a.txt", "the tax return"), "tax.txt", key)
        val b = importFile(root, fileOf("b.txt", "the shopping list"), "shopping.txt", key)

        // An attacker with write access to the folder but no password swaps the ciphertext.
        val blobA = File(root, a.storageName)
        val blobB = File(root, b.storageName)
        val held = File(temp.root, "held").also { blobA.copyTo(it, overwrite = true) }
        blobB.copyTo(blobA, overwrite = true)
        held.copyTo(blobB, overwrite = true)

        // The point is that it fails rather than quietly returning the other file's contents.
        assertThrows(ArchiveCrypto.BadPasswordOrTamperedException::class.java) {
            exportFile(root, a.storageName, File(temp.root, "out.txt"), key)
        }
    }

    @Test
    fun `a blob moved to another name does not open under it`() {
        val (dir, key) = newVault()
        val root = Vault.treeRoot(dir)
        val a = importFile(root, fileOf("a.txt", "secret"), "a.txt", key)
        val b = importFile(root, fileOf("b.txt", "decoy"), "b.txt", key)

        File(root, a.storageName).copyTo(File(root, b.storageName), overwrite = true)

        assertThrows(ArchiveCrypto.BadPasswordOrTamperedException::class.java) {
            exportFile(root, b.storageName, File(temp.root, "out.txt"), key)
        }
    }

    @Test
    fun `a subdirectory index cannot stand in for the root's`() {
        val (dir, key) = newVault()
        val root = Vault.treeRoot(dir)
        importFile(root, fileOf("r.txt", "outer"), "outside.txt", key)
        val sub = Vault.createDirectory(root, "Photos", key)

        // Both indexes are sealed under the same master key, so only the binding separates them.
        File(sub, ".idx").copyTo(File(root, ".idx"), overwrite = true)

        assertThrows(ArchiveCrypto.BadPasswordOrTamperedException::class.java) {
            Vault.list(root, key)
        }
    }

    @Test
    fun `an index cannot be passed off as a file blob`() {
        val (dir, key) = newVault()
        val root = Vault.treeRoot(dir)
        val entry = importFile(root, fileOf("a.txt", "contents"), "a.txt", key)

        // Domain separation: without the F:/D: tags a directory whose storage name matched a
        // blob's would be interchangeable with it.
        File(root, ".idx").copyTo(File(root, entry.storageName), overwrite = true)

        assertThrows(ArchiveCrypto.BadPasswordOrTamperedException::class.java) {
            exportFile(root, entry.storageName, File(temp.root, "out.txt"), key)
        }
    }

    // --- directories ------------------------------------------------------------

    @Test
    fun `nests directories and keeps their contents separate`() {
        val (dir, key) = newVault()
        val root = Vault.treeRoot(dir)
        val photos = Vault.createDirectory(root, "Photos", key)
        importFile(photos, fileOf("p.txt", "inner"), "inside.txt", key)
        importFile(root, fileOf("r.txt", "outer"), "outside.txt", key)

        val rootEntries = Vault.list(root, key).sortedBy { it.name }
        assertEquals(listOf("Photos", "outside.txt"), rootEntries.map { it.name })
        assertTrue(rootEntries.first { it.name == "Photos" }.isDirectory)

        val inner = Vault.list(photos, key).single()
        assertEquals("inside.txt", inner.name)
    }

    @Test
    fun `refuses a duplicate name in the same directory`() {
        val (dir, key) = newVault()
        val root = Vault.treeRoot(dir)
        importFile(root, fileOf("x.txt", "one"), "same.txt", key)
        assertThrows(IOException::class.java) {
            importFile(root, fileOf("y.txt", "two"), "same.txt", key)
        }
        assertThrows(IOException::class.java) { Vault.createDirectory(root, "same.txt", key) }
    }

    @Test
    fun `renames without touching the ciphertext`() {
        val (dir, key) = newVault()
        val root = Vault.treeRoot(dir)
        importFile(root, fileOf("f.txt", "unchanged"), "before.txt", key)
        val before = Vault.list(root, key).single()
        val bytesBefore = File(root, before.storageName).readBytes()

        Vault.rename(root, before.storageName, "after.txt", key)

        val after = Vault.list(root, key).single()
        assertEquals("after.txt", after.name)
        assertEquals(before.storageName, after.storageName)
        assertArrayEquals(bytesBefore, File(root, after.storageName).readBytes())
    }

    @Test
    fun `deletes an entry and its blob`() {
        val (dir, key) = newVault()
        val root = Vault.treeRoot(dir)
        importFile(root, fileOf("f.txt", "bye"), "doomed.txt", key)
        val entry = Vault.list(root, key).single()
        val blob = File(root, entry.storageName)
        assertTrue(blob.exists())

        Vault.delete(root, entry.storageName, key)

        assertFalse(blob.exists())
        assertEquals(0, Vault.list(root, key).size)
    }

    @Test
    fun `deleting a directory removes what is inside it`() {
        val (dir, key) = newVault()
        val root = Vault.treeRoot(dir)
        val sub = Vault.createDirectory(root, "Sub", key)
        importFile(sub, fileOf("s.txt", "gone too"), "inner.txt", key)

        Vault.delete(root, Vault.list(root, key).single().storageName, key)

        assertFalse(sub.exists())
        assertEquals(0, Vault.list(root, key).size)
    }

    // --- larger content ---------------------------------------------------------

    @Test
    fun `round-trips content spanning several frames`() {
        val (dir, key) = newVault()
        val root = Vault.treeRoot(dir)
        val big = ByteArray(64 * 1024 * 3 + 11) { (it % 251).toByte() }
        val source = File(temp.root, "big.bin").apply { writeBytes(big) }

        importFile(root, source, "big.bin", key)
        val out = File(temp.root, "big-out.bin")
        exportFile(root, Vault.list(root, key).single().storageName, out, key)

        assertArrayEquals(big, out.readBytes())
    }

    @Test
    fun `reports progress totalling the plaintext length`() {
        val (dir, key) = newVault()
        val root = Vault.treeRoot(dir)
        val body = "x".repeat(5000)
        var imported = 0L
        importFile(root, fileOf("p.txt", body), "p.txt", key) { imported += it }
        assertEquals(body.length.toLong(), imported)

        var exported = 0L
        exportFile(
            root, Vault.list(root, key).single().storageName,
            File(temp.root, "p-out.txt"), key,
        ) { exported += it }
        assertEquals(body.length.toLong(), exported)
    }
}
