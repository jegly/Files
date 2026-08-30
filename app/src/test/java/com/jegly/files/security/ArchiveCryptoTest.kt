package com.jegly.files.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import kotlin.random.Random

/**
 * The container format is the one place in this app where a silent bug is worse than a crash: a
 * broken tag check looks exactly like a working one until somebody relies on it. So these tests
 * are mostly adversarial — they assert what the format *refuses*, not what it accepts.
 *
 * Pure JVM: ArchiveCrypto uses javax.crypto and java.security only, no android.* at all, so the
 * real cipher runs here rather than a stub.
 */
class ArchiveCryptoTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val password = "correct horse battery staple".toCharArray()

    private fun plaintextOf(size: Int): ByteArray = Random(size).nextBytes(size)

    private fun sealFile(contents: ByteArray, pw: CharArray = password): Pair<File, File> {
        val source = File(temp.root, "plain.bin").apply { writeBytes(contents) }
        val sealed = File(temp.root, "sealed.jfsec")
        ArchiveCrypto.encrypt(source, sealed, pw)
        return source to sealed
    }

    private fun open(sealed: File, pw: CharArray = password): ByteArray {
        val out = File(temp.root, "opened-${sealed.name}-${System.nanoTime()}.bin")
        ArchiveCrypto.decrypt(sealed, out, pw)
        return out.readBytes()
    }

    // --- round trips ------------------------------------------------------------

    @Test
    fun `round-trips an empty file`() {
        val (_, sealed) = sealFile(ByteArray(0))
        assertEquals(0, open(sealed).size)
    }

    @Test
    fun `round-trips a small file`() {
        val contents = plaintextOf(1000)
        val (_, sealed) = sealFile(contents)
        assertArrayEquals(contents, open(sealed))
    }

    /**
     * The boundary the writer's "a full frame is never the last" rule exists for. At exactly one
     * frame the writer must still emit a trailing empty frame, or the reader would take the full
     * frame as final and its last=true AAD would not match.
     */
    @Test
    fun `round-trips a file that is exactly one frame`() {
        val contents = plaintextOf(64 * 1024)
        val (_, sealed) = sealFile(contents)
        assertArrayEquals(contents, open(sealed))
    }

    @Test
    fun `round-trips a file spanning several frames with a partial tail`() {
        val contents = plaintextOf(64 * 1024 * 3 + 517)
        val (_, sealed) = sealFile(contents)
        assertArrayEquals(contents, open(sealed))
    }

    @Test
    fun `reports progress totalling the plaintext length`() {
        val contents = plaintextOf(64 * 1024 * 2 + 10)
        val source = File(temp.root, "p.bin").apply { writeBytes(contents) }
        val sealed = File(temp.root, "s.jfsec")
        var written = 0L
        ArchiveCrypto.encrypt(source, sealed, password) { written += it }
        assertEquals(contents.size.toLong(), written)
    }

    // --- refusals ---------------------------------------------------------------

    @Test
    fun `refuses the wrong password`() {
        val (_, sealed) = sealFile(plaintextOf(5000))
        assertThrows(ArchiveCrypto.BadPasswordOrTamperedException::class.java) {
            open(sealed, "wrong password".toCharArray())
        }
    }

    @Test
    fun `refuses a password that differs by one character`() {
        val (_, sealed) = sealFile(plaintextOf(5000))
        assertThrows(ArchiveCrypto.BadPasswordOrTamperedException::class.java) {
            open(sealed, "correct horse battery stapl3".toCharArray())
        }
    }

    @Test
    fun `refuses a flipped bit in the ciphertext`() {
        val (_, sealed) = sealFile(plaintextOf(5000))
        val bytes = sealed.readBytes()
        // Past the 42-byte header, so this lands in frame data rather than metadata.
        bytes[100] = (bytes[100].toInt() xor 0x01).toByte()
        sealed.writeBytes(bytes)
        assertThrows(ArchiveCrypto.BadPasswordOrTamperedException::class.java) { open(sealed) }
    }

    /**
     * Cutting whole frames off the end leaves a file that is internally consistent frame by frame
     * — every remaining tag is genuine — so nothing about the ciphertext itself gives it away.
     *
     * What catches it is the writer's rule that the final frame is always strictly shorter than
     * FRAME_SIZE: the file now ends on a frame boundary, so the reader looks for the short final
     * frame that must exist and finds end-of-stream. Verified by experiment, not assumption — the
     * AAD's last-frame flag can be removed and this test still passes.
     */
    @Test
    fun `refuses an archive with whole frames cut off the end`() {
        val (_, sealed) = sealFile(plaintextOf(64 * 1024 * 3 + 99))
        val bytes = sealed.readBytes()
        val oneFrame = 64 * 1024 + 16
        sealed.writeBytes(bytes.copyOf(42 + oneFrame))
        assertThrows(ArchiveCrypto.BadPasswordOrTamperedException::class.java) { open(sealed) }
    }

    @Test
    fun `refuses an archive cut mid-frame`() {
        val (_, sealed) = sealFile(plaintextOf(5000))
        val bytes = sealed.readBytes()
        sealed.writeBytes(bytes.copyOf(bytes.size - 5))
        assertThrows(ArchiveCrypto.BadPasswordOrTamperedException::class.java) { open(sealed) }
    }

    /**
     * Frames are equal-sized, so swapping two produces a structurally valid file. The nonce is
     * what rejects it: the reader builds prefix||index from its own position, so a frame sealed
     * at index 1 and read at index 0 is opened under a nonce it never saw. Not the AAD — this
     * test passes with the AAD's index removed.
     */
    @Test
    fun `refuses reordered frames`() {
        val (_, sealed) = sealFile(plaintextOf(64 * 1024 * 3 + 40))
        val bytes = sealed.readBytes()
        val frame = 64 * 1024 + 16
        val first = bytes.copyOfRange(42, 42 + frame)
        val second = bytes.copyOfRange(42 + frame, 42 + frame * 2)
        second.copyInto(bytes, 42)
        first.copyInto(bytes, 42 + frame)
        sealed.writeBytes(bytes)
        assertThrows(ArchiveCrypto.BadPasswordOrTamperedException::class.java) { open(sealed) }
    }

    /**
     * The iteration count is read and used *before* any tag has been verified, so an attacker
     * lowering it to 1 would make a brute force cheap. It fails because iterations feed PBKDF2:
     * a different count derives a different key, and the first frame will not open under it.
     * parseHeader's bounds check is the separate, earlier guard against a header claiming two
     * billion iterations to hang the app before any of this runs.
     */
    @Test
    fun `refuses a weakened iteration count in the header`() {
        val (_, sealed) = sealFile(plaintextOf(2000))
        val bytes = sealed.readBytes()
        // Header offset 10..13 is the big-endian iteration count.
        bytes[10] = 0; bytes[11] = 0; bytes[12] = 0; bytes[13] = 1
        sealed.writeBytes(bytes)
        assertThrows(ArchiveCrypto.BadPasswordOrTamperedException::class.java) { open(sealed) }
    }

    @Test
    fun `refuses a swapped salt`() {
        val (_, sealed) = sealFile(plaintextOf(2000))
        val bytes = sealed.readBytes()
        bytes[14] = (bytes[14].toInt() xor 0xFF).toByte()
        sealed.writeBytes(bytes)
        assertThrows(ArchiveCrypto.BadPasswordOrTamperedException::class.java) { open(sealed) }
    }

    @Test
    fun `refuses a file that is not a container`() {
        val notOurs = File(temp.root, "random.bin").apply { writeBytes(plaintextOf(500)) }
        val out = File(temp.root, "out.bin")
        assertThrows(IOException::class.java) {
            ArchiveCrypto.decrypt(notOurs, out, password)
        }
    }

    // --- format properties ------------------------------------------------------

    @Test
    fun `recognises its own containers and nothing else`() {
        val (source, sealed) = sealFile(plaintextOf(3000))
        assertTrue(ArchiveCrypto.isContainer(sealed))
        assertFalse(ArchiveCrypto.isContainer(source))
        assertFalse(ArchiveCrypto.isContainer(File(temp.root, "does-not-exist")))
    }

    /**
     * Fresh salt and nonce prefix per file, so sealing identical plaintext twice under the same
     * password must not produce identical bytes. Equal output would mean an observer could tell
     * two archives hold the same data — and, far worse, would imply a repeated GCM nonce.
     */
    @Test
    fun `sealing the same input twice produces different ciphertext`() {
        val contents = plaintextOf(4000)
        val source = File(temp.root, "p.bin").apply { writeBytes(contents) }
        val a = File(temp.root, "a.jfsec")
        val b = File(temp.root, "b.jfsec")
        ArchiveCrypto.encrypt(source, a, password)
        ArchiveCrypto.encrypt(source, b, password)

        assertNotEquals(a.readBytes().toList(), b.readBytes().toList())
        // Both still open to the same thing.
        assertArrayEquals(contents, open(a))
        assertArrayEquals(contents, open(b))
    }

    // --- streaming writer -------------------------------------------------------

    /**
     * The streaming writer has to produce byte-for-byte the same framing as the File-based one,
     * including the trailing short frame, or archives written by one path fail to open on the
     * other. Both boundary cases are covered: an exact multiple of the frame size, and empty.
     */
    @Test
    fun `streaming writer produces archives the reader accepts`() {
        for (size in listOf(0, 1, 5000, 64 * 1024, 64 * 1024 * 2, 64 * 1024 * 2 + 7)) {
            val contents = plaintextOf(size)
            val sealed = File(temp.root, "stream-$size.jfsec")
            sealed.outputStream().use { raw ->
                ArchiveCrypto.encryptingStream(raw, password).use { it.write(contents) }
            }
            val out = File(temp.root, "stream-out-$size.bin")
            ArchiveCrypto.decrypt(sealed, out, password)
            assertArrayEquals("size $size", contents, out.readBytes())
        }
    }

    @Test
    fun `streaming writer tolerates many small writes`() {
        val contents = plaintextOf(64 * 1024 + 1234)
        val sealed = File(temp.root, "dribble.jfsec")
        sealed.outputStream().use { raw ->
            ArchiveCrypto.encryptingStream(raw, password).use { out ->
                // Writes that straddle frame boundaries at awkward offsets.
                var i = 0
                while (i < contents.size) {
                    val n = minOf(777, contents.size - i)
                    out.write(contents, i, n)
                    i += n
                }
            }
        }
        val opened = File(temp.root, "dribble-out.bin")
        ArchiveCrypto.decrypt(sealed, opened, password)
        assertArrayEquals(contents, opened.readBytes())
    }

    @Test
    fun `streaming writer still rejects the wrong password`() {
        val sealed = File(temp.root, "s.jfsec")
        sealed.outputStream().use { raw ->
            ArchiveCrypto.encryptingStream(raw, password).use { it.write(plaintextOf(3000)) }
        }
        assertThrows(ArchiveCrypto.BadPasswordOrTamperedException::class.java) {
            ArchiveCrypto.decrypt(sealed, File(temp.root, "o.bin"), "nope".toCharArray())
        }
    }

    @Test
    fun `does not mutate the caller's password array`() {
        val pw = "keep me intact".toCharArray()
        val original = pw.copyOf()
        val (_, sealed) = sealFile(plaintextOf(1000), pw)
        ArchiveCrypto.decrypt(sealed, File(temp.root, "o.bin"), pw)
        assertArrayEquals(original, pw)
    }
}
