package com.jegly.files.ops

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import com.jegly.files.security.Vault
import com.jegly.files.security.VaultSession
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Local JVM tests for the half of the app that can destroy data.
 *
 * These run against a real filesystem in a TemporaryFolder, with no Android on the classpath —
 * [FileOperations] deliberately depends on nothing outside java.io/java.nio and coroutines, so
 * the copy, move, delete and extract paths are testable without an emulator.
 *
 * The recursion cases are the reason this file exists. Copying a directory into itself used to
 * make copyTree descend into the directory it was in the middle of creating, recursing until
 * PATH_MAX or a StackOverflowError ended it and leaving hundreds of nested directories behind.
 *
 * Each recursion test was checked by disabling the guard and confirming it fails — a regression
 * test nobody has watched fail is just an assertion. Doing that also corrected the original
 * diagnosis: Move does *not* lose the source, because the exception ending the recursion
 * short-circuits the deleteRecursively(source) that follows copyTree in the Move branch. The
 * move test below pins that, so the distinction survives the next refactor.
 */
class FileOperationsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun run(
        kind: OpKind,
        sources: List<File>,
        destination: File?,
        policy: ConflictPolicy = ConflictPolicy.KeepBoth,
        password: CharArray? = null,
    ): OpProgress.Finished =
        runBlocking {
            FileOperations().run(kind, sources, destination, policy, password).toList()
        }.filterIsInstance<OpProgress.Finished>().single()

    private fun File.child(relative: String, contents: String = "x"): File =
        File(this, relative).apply {
            parentFile?.mkdirs()
            writeText(contents)
        }

    // --- recursion guards -------------------------------------------------------

    @Test
    fun `copying a folder into itself is refused and changes nothing`() {
        val source = temp.newFolder("photos")
        source.child("a.txt", "keep me")

        val result = run(OpKind.Copy, listOf(source), source)

        assertEquals(0, result.succeeded)
        assertEquals(1, result.failures.size)
        assertTrue(result.failures.single().reason.contains("into itself"))
        // Nothing new appeared, and nothing was consumed producing it.
        assertEquals(listOf("a.txt"), source.list()!!.sorted())
        assertEquals("keep me", File(source, "a.txt").readText())
    }

    @Test
    fun `copying a folder into its own descendant is refused`() {
        val source = temp.newFolder("photos")
        val nested = File(source, "2024").apply { mkdirs() }
        source.child("a.txt")

        val result = run(OpKind.Copy, listOf(source), nested)

        assertEquals(0, result.succeeded)
        assertEquals(1, result.failures.size)
        assertTrue(result.failures.single().reason.contains("inside itself"))
        assertEquals(0, nested.list()!!.size)
    }

    /**
     * renameTo fails for a move into a descendant, so the old code fell through to copyTree and
     * recursed. The source surviving is not the guard's doing — it held before the guard too,
     * because the exception ending the recursion skips the deleteRecursively(source) below
     * copyTree. Asserted anyway: it is the property that makes this bug survivable, and a
     * refactor that moved the delete into a finally block would quietly turn it into data loss.
     *
     * The refusal message and the absence of junk are what actually regress without the guard.
     */
    @Test
    fun `moving a folder into its own descendant is refused cleanly`() {
        val source = temp.newFolder("docs")
        val nested = File(source, "archive").apply { mkdirs() }
        source.child("important.txt", "irreplaceable")

        val result = run(OpKind.Move, listOf(source), nested)

        assertEquals(0, result.succeeded)
        assertEquals(1, result.failures.size)
        assertTrue(result.failures.single().reason.contains("inside itself"))
        // Refused up front, so the destination was never touched.
        assertEquals(0, nested.list()!!.size)
        assertTrue(source.isDirectory)
        assertEquals("irreplaceable", File(source, "important.txt").readText())
    }

    @Test
    fun `a refused source does not abort the rest of the batch`() {
        // Matches AOSP CopyJob.start(), which calls onFileFailed() for the recursive source and
        // carries on with the remaining ones rather than tearing the whole job down.
        val recursive = temp.newFolder("self")
        val innocent = temp.newFolder("other").also { it.child("f.txt") }

        val result = run(OpKind.Copy, listOf(recursive, innocent), recursive)

        assertEquals(1, result.succeeded)
        assertEquals(1, result.failures.size)
        assertTrue(File(recursive, "other/f.txt").isFile)
    }

    // --- ordinary transfers -----------------------------------------------------

    @Test
    fun `copy reproduces a tree and leaves the source alone`() {
        val source = temp.newFolder("src")
        source.child("top.txt", "one")
        source.child("deep/nested.txt", "two")
        val dest = temp.newFolder("dest")

        val result = run(OpKind.Copy, listOf(source), dest)

        assertEquals(0, result.failures.size)
        assertEquals("one", File(dest, "src/top.txt").readText())
        assertEquals("two", File(dest, "src/deep/nested.txt").readText())
        assertTrue(File(source, "top.txt").isFile)
    }

    @Test
    fun `move removes the source once it has arrived`() {
        val source = temp.newFolder("src").also { it.child("a.txt", "payload") }
        val dest = temp.newFolder("dest")

        val result = run(OpKind.Move, listOf(source), dest)

        assertEquals(0, result.failures.size)
        assertEquals("payload", File(dest, "src/a.txt").readText())
        assertFalse(source.exists())
    }

    @Test
    fun `keep both leaves the existing file untouched`() {
        val source = temp.newFolder("src").also { it.child("note.txt", "new") }
        val dest = temp.newFolder("dest")
        File(dest, "src").mkdirs()
        File(dest, "src").child("note.txt", "old")

        run(OpKind.Copy, listOf(File(source, "note.txt")), File(dest, "src"), ConflictPolicy.KeepBoth)

        assertEquals("old", File(dest, "src/note.txt").readText())
        assertEquals("new", File(dest, "src/note (2).txt").readText())
    }

    @Test
    fun `skip declines to touch a colliding name`() {
        val source = temp.newFolder("src").also { it.child("note.txt", "new") }
        val dest = temp.newFolder("dest").also { it.child("note.txt", "old") }

        val result = run(
            OpKind.Copy,
            listOf(File(source, "note.txt")),
            dest,
            ConflictPolicy.Skip,
        )

        assertEquals(1, result.skipped)
        assertEquals("old", File(dest, "note.txt").readText())
    }

    @Test
    fun `overwrite replaces the existing file`() {
        val source = temp.newFolder("src").also { it.child("note.txt", "new") }
        val dest = temp.newFolder("dest").also { it.child("note.txt", "old") }

        run(OpKind.Copy, listOf(File(source, "note.txt")), dest, ConflictPolicy.Overwrite)

        assertEquals("new", File(dest, "note.txt").readText())
    }

    // --- symlinks ---------------------------------------------------------------

    @Test
    fun `deleting a symlinked folder unlinks it without emptying its target`() {
        val real = temp.newFolder("real").also { it.child("treasure.txt", "still here") }
        val holder = temp.newFolder("holder")
        val link = File(holder, "shortcut")
        Files.createSymbolicLink(link.toPath(), real.toPath())

        val result = run(OpKind.Delete, listOf(link), null)

        assertEquals(0, result.failures.size)
        assertFalse(Files.exists(link.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertTrue(real.isDirectory)
        assertEquals("still here", File(real, "treasure.txt").readText())
    }

    // --- archives ---------------------------------------------------------------

    private fun zipOf(name: String, vararg entries: Pair<String, String>): File {
        val archive = File(temp.root, name)
        ZipOutputStream(archive.outputStream()).use { zip ->
            entries.forEach { (path, body) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
        }
        return archive
    }

    @Test
    fun `extract refuses an entry that escapes the destination`() {
        val archive = zipOf("evil.zip", "../escaped.txt" to "pwned")
        val dest = File(temp.root, "out")

        val result = run(OpKind.Extract, listOf(archive), dest)

        assertEquals(1, result.failures.size)
        assertTrue(result.failures.single().reason.contains("Unsafe path"))
        assertFalse(File(temp.root, "escaped.txt").exists())
    }

    @Test
    fun `extract writes every entry and counts them individually`() {
        val archive = zipOf(
            "good.zip",
            "a.txt" to "one",
            "sub/b.txt" to "two",
            "sub/c.txt" to "three",
        )
        val dest = File(temp.root, "out")

        val result = run(OpKind.Extract, listOf(archive), dest)

        assertEquals(0, result.failures.size)
        // Previously this reported 1 — once per archive rather than once per file written.
        assertEquals(3, result.succeeded)
        assertEquals("two", File(dest, "sub/b.txt").readText())
    }

    // --- encrypted archives -----------------------------------------------------

    @Test
    fun `compress with a password round-trips through extract`() {
        val password = "a good long passphrase".toCharArray()
        val source = temp.newFolder("secrets")
        source.child("notes.txt", "confidential")
        source.child("deep/more.txt", "also confidential")
        val archive = File(temp.root, "secrets.zip.jfsec")

        run(OpKind.Compress, listOf(source), archive, password = password)
        val dest = File(temp.root, "opened")
        val result = run(OpKind.Extract, listOf(archive), dest, password = password)

        assertEquals(0, result.failures.size)
        assertEquals("confidential", File(dest, "secrets/notes.txt").readText())
        assertEquals("also confidential", File(dest, "secrets/deep/more.txt").readText())
    }

    /**
     * The property the whole container exists for: unlike a WinZip-AES zip, whose central
     * directory is plaintext, nothing readable survives in the output — not the contents and not
     * the filenames.
     */
    @Test
    fun `an encrypted archive leaks neither contents nor filenames`() {
        val password = "a good long passphrase".toCharArray()
        val source = temp.newFolder("payroll")
        source.child("salaries-2026.csv", "alice,100000")
        val archive = File(temp.root, "payroll.zip.jfsec")

        run(OpKind.Compress, listOf(source), archive, password = password)

        val raw = archive.readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(raw.contains("salaries-2026"))
        assertFalse(raw.contains("alice"))
        assertFalse(raw.contains("payroll"))
        // A plain zip of the same tree does leak the name, which is the contrast being drawn.
        val plain = File(temp.root, "payroll.zip")
        run(OpKind.Compress, listOf(source), plain)
        assertTrue(plain.readBytes().toString(Charsets.ISO_8859_1).contains("salaries-2026"))
    }

    @Test
    fun `extracting an encrypted archive with the wrong password fails and writes nothing`() {
        val source = temp.newFolder("stuff").also { it.child("a.txt", "x") }
        val archive = File(temp.root, "stuff.zip.jfsec")
        run(OpKind.Compress, listOf(source), archive, password = "right".toCharArray())

        val dest = File(temp.root, "out")
        val result = run(OpKind.Extract, listOf(archive), dest, password = "wrong".toCharArray())

        assertEquals(1, result.failures.size)
        assertEquals(0, dest.list()!!.size)
    }

    @Test
    fun `extracting an encrypted archive without a password reports it needs one`() {
        val source = temp.newFolder("stuff").also { it.child("a.txt", "x") }
        val archive = File(temp.root, "stuff.zip.jfsec")
        run(OpKind.Compress, listOf(source), archive, password = "right".toCharArray())

        val result = run(OpKind.Extract, listOf(archive), File(temp.root, "out"))

        assertEquals(1, result.failures.size)
        assertTrue(result.failures.single().reason.contains("needs a password"))
    }

    /** The sealed path must enforce Zip Slip exactly as the plain one does. */
    @Test
    fun `an encrypted archive cannot escape the destination either`() {
        val password = "pw".toCharArray()
        // Build the hostile zip first, then seal it, so the traversal is inside the ciphertext.
        val hostile = zipOf("hostile.zip", "../escaped.txt" to "pwned")
        val archive = File(temp.root, "hostile.zip.jfsec")
        java.io.FileOutputStream(archive).use { raw ->
            com.jegly.files.security.ArchiveCrypto.encryptingStream(raw, password).use { out ->
                out.write(hostile.readBytes())
            }
        }

        val dest = File(temp.root, "out")
        val result = run(OpKind.Extract, listOf(archive), dest, password = password)

        assertEquals(1, result.failures.size)
        assertTrue(result.failures.single().reason.contains("Unsafe path"))
        assertFalse(File(temp.root, "escaped.txt").exists())
    }

    // --- vault transfers --------------------------------------------------------

    private val vaultPassword = "a decent vault password".toCharArray()

    private fun newVault(name: String): Pair<File, File> {
        val dir = File(temp.root, name)
        Vault.create(dir, vaultPassword)
        VaultSession.unlock(dir, vaultPassword.copyOf())
        return dir to Vault.treeRoot(dir)
    }

    @After
    fun closeVaults() = VaultSession.lockAll()

    @Test
    fun `copying into a vault encrypts, and copying back out decrypts`() {
        val (_, root) = newVault("Vault")
        val source = temp.newFolder("plain").also {
            it.child("secret.txt", "classified")
            it.child("nested/deeper.txt", "also classified")
        }

        val inbound = run(OpKind.Copy, listOf(source), root)
        assertEquals(0, inbound.failures.size)

        // Present under its real name inside, and unreadable from outside.
        val stored = Vault.list(root, VaultSession.keyFor(File(temp.root, "Vault"))!!).single()
        assertEquals("plain", stored.name)
        val onDisk = File(temp.root, "Vault").walkTopDown().filter { it.isFile }
            .joinToString("") { it.readBytes().toString(Charsets.ISO_8859_1) }
        assertFalse(onDisk.contains("classified"))
        assertFalse(onDisk.contains("secret.txt"))

        val out = temp.newFolder("recovered")
        val outbound = run(OpKind.Copy, listOf(File(root, stored.storageName)), out)
        assertEquals(0, outbound.failures.size)
        assertEquals("classified", File(out, "plain/secret.txt").readText())
        assertEquals("also classified", File(out, "plain/nested/deeper.txt").readText())
    }

    @Test
    fun `moving into a vault removes the plaintext original`() {
        val (_, root) = newVault("Vault")
        val source = temp.newFolder("plain").also { it.child("a.txt", "moved") }

        val result = run(OpKind.Move, listOf(source), root)

        assertEquals(0, result.failures.size)
        assertFalse("the plaintext original must not survive a move in", source.exists())
    }

    @Test
    fun `deleting inside a vault removes the blob and the index entry`() {
        val (dir, root) = newVault("Vault")
        run(OpKind.Copy, listOf(temp.newFolder("p").also { it.child("f.txt", "x") }), root)
        val key = VaultSession.keyFor(dir)!!
        val entry = Vault.list(root, key).single()
        val blob = File(root, entry.storageName)

        val result = run(OpKind.Delete, listOf(blob), null)

        assertEquals(0, result.failures.size)
        assertFalse(blob.exists())
        assertEquals(0, Vault.list(root, key).size)
    }

    @Test
    fun `moving between two vaults re-encrypts under the destination key`() {
        val (fromDir, fromRoot) = newVault("From")
        val (toDir, toRoot) = newVault("To")
        run(OpKind.Copy, listOf(temp.newFolder("p").also { it.child("f.txt", "travelling") }), fromRoot)
        val fromKey = VaultSession.keyFor(fromDir)!!
        val blob = File(fromRoot, Vault.list(fromRoot, fromKey).single().storageName)

        val result = run(OpKind.Move, listOf(blob), toRoot)

        assertEquals(0, result.failures.size)
        assertEquals(0, Vault.list(fromRoot, fromKey).size)
        val toKey = VaultSession.keyFor(toDir)!!
        assertEquals("p", Vault.list(toRoot, toKey).single().name)
    }

    /** A locked vault must refuse rather than silently writing plaintext into it. */
    @Test
    fun `a locked vault refuses transfers`() {
        val (dir, root) = newVault("Vault")
        VaultSession.lock(dir)

        val result = run(
            OpKind.Copy,
            listOf(temp.newFolder("p").also { it.child("f.txt", "x") }),
            root,
        )

        assertEquals(1, result.failures.size)
        assertTrue(result.failures.single().reason.contains("locked"))
    }

    /**
     * Copying a vault folder itself is an ordinary directory copy of its ciphertext. Decrypting
     * and re-encrypting it would need the password, be slower, and change the bytes the user
     * asked to duplicate.
     */
    @Test
    fun `copying a whole vault folder copies the ciphertext verbatim`() {
        val (dir, root) = newVault("Vault")
        run(OpKind.Copy, listOf(temp.newFolder("p").also { it.child("f.txt", "x") }), root)
        val dest = temp.newFolder("backup")

        val result = run(OpKind.Copy, listOf(dir), dest)

        assertEquals(0, result.failures.size)
        val copied = File(dest, "Vault")
        assertTrue(Vault.isVault(copied))
        assertArrayEquals(
            File(dir, Vault.HEADER_NAME).readBytes(),
            File(copied, Vault.HEADER_NAME).readBytes(),
        )
    }

    @Test
    fun `compress then extract round-trips a tree`() {
        val source = temp.newFolder("payload")
        source.child("a.txt", "one")
        source.child("deep/b.txt", "two")
        val archive = File(temp.root, "out.zip")

        run(OpKind.Compress, listOf(source), archive)
        val dest = File(temp.root, "unpacked")
        run(OpKind.Extract, listOf(archive), dest)

        assertEquals("one", File(dest, "payload/a.txt").readText())
        assertEquals("two", File(dest, "payload/deep/b.txt").readText())
    }
}
