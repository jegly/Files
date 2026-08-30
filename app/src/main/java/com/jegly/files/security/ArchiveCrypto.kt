package com.jegly.files.security

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password-based authenticated encryption for whole archives.
 *
 * WHY THIS AND NOT AN ENCRYPTED ZIP. java.util.zip cannot read or write encrypted entries, and
 * Commons Compress supports neither direction either, so a standard AES zip would mean taking a
 * third-party dependency (zip4j is effectively the only Java option) into the trusted path. This
 * container avoids that using javax.crypto alone. The trade is interoperability: only this app
 * can open what it produces. A WinZip-AES zip from another tool is NOT readable here.
 *
 * One thing this format does that a standard AES zip cannot: hide filenames. WinZip AE-1/AE-2
 * encrypts entry contents but leaves the central directory in plaintext, so anyone can list an
 * encrypted archive's contents without the password. Here the whole zip — central directory
 * included — is inside the ciphertext.
 *
 * FRAMED, NOT WHOLE-FILE. The plaintext is split into fixed-size frames, each sealed
 * independently with AES-256-GCM. Two reasons, both correctness rather than taste:
 *
 *  - CipherInputStream in GCM mode hands out plaintext before the authentication tag has been
 *    checked, and swallows AEADBadTagException on close in some implementations. Whole-file GCM
 *    through that API means acting on unverified data. Here the most that is ever released
 *    unverified is nothing: a frame is authenticated by doFinal() before its plaintext is written.
 *  - Cipher.doFinal() over a multi-gigabyte archive would have to hold it in memory.
 *
 * This is the STREAM construction used by age and Tink's streaming AEAD. Framing has to defend
 * against more than bit-flips — reordering, splicing and truncation all produce files that are
 * structurally valid — and it is worth being precise about what actually stops each, because the
 * obvious answer is wrong. Measured by removing the AAD extras and re-running the tests: all of
 * them still passed, so the AAD is NOT the mechanism. What is:
 *
 *  - Reordering and splicing are stopped by the NONCE. The reader builds prefix||index from its
 *    own position in the file, never from the file's contents, so a frame moved anywhere else
 *    decrypts under a nonce it was not sealed with and the tag fails.
 *  - Truncation is stopped by the WRITER'S FRAMING RULE below: the last frame is always strictly
 *    shorter than FRAME_SIZE, so a file cut at a frame boundary hits end-of-stream exactly where
 *    a short final frame was required, and reading zero bytes is an error rather than an end.
 *  - Header tampering is stopped by KEY DERIVATION: salt and iterations feed PBKDF2 and the nonce
 *    prefix feeds the nonce, so altering any of them yields a different key or nonce and fails.
 *
 * The AAD binding header, index and last-flag is kept anyway, deliberately. It is redundant today
 * and costs nothing, and it is the only one of these three that survives someone later
 * "optimising away" the trailing short frame or reading the nonce from the file. Do not remove it
 * on the grounds that the tests pass without it — they do, and that is the point.
 *
 * WHAT THE AAD IS NOT REDUNDANT FOR. All three mechanisms above protect a frame's position
 * *within one file*. None of them says anything about *which* file it is, because the nonce
 * prefix is fresh random per file rather than derived from anything. So under a single key every
 * container is interchangeable with every other: swap two whole files and each still opens
 * perfectly, having proved only "someone with the key wrote this". That is fine for a sealed
 * archive, which is a standalone file the user points at deliberately, and not fine at all for a
 * vault, where the app resolves a name to a blob on the user's behalf. The caller-supplied
 * `context` closes it — see [encryptingStream] and Vault.
 *
 * Layout, all integers big-endian:
 *
 *     0   8   magic "JFSEC\0\0\0"
 *     8   1   format version
 *     9   1   KDF id (1 = PBKDF2-HMAC-SHA256)
 *     10  4   KDF iterations
 *     14  16  KDF salt
 *     30  8   nonce prefix, random per file
 *     38  4   frame plaintext size
 *     42      frames: ciphertext || 16-byte GCM tag, repeated
 *
 * The header is not stored in the clear as an afterthought — it is fed to every frame as
 * associated data, so tampering with the iteration count, the salt or the frame size invalidates
 * the very first tag rather than silently weakening key derivation.
 *
 * The writer always emits a final frame strictly smaller than [FRAME_SIZE], adding an empty one
 * when the plaintext divides exactly. That makes "short frame" and "last frame" the same thing,
 * so the reader needs no lookahead, and a truncated file is caught: the frame that ends the file
 * early was sealed with last=false and fails to open as last=true.
 */
object ArchiveCrypto {

    /** Extension for containers this app produces. */
    const val EXTENSION = "jfsec"

    private val MAGIC = byteArrayOf(
        'J'.code.toByte(), 'F'.code.toByte(), 'S'.code.toByte(), 'E'.code.toByte(),
        'C'.code.toByte(), 0, 0, 0,
    )

    /**
     * 2 adds the caller-supplied AAD context. The context is never stored, so a v1 container is
     * indistinguishable from a v2 one on disk and would simply fail to authenticate with a
     * misleading "wrong password"; the bump turns that into an explicit unsupported-version
     * error. Nothing has ever run on a device, so there are no v1 containers to migrate.
     */
    private const val VERSION = 2
    private const val KDF_PBKDF2_SHA256 = 1

    /**
     * No derivation: the caller supplied a 256-bit key directly.
     *
     * This is what a vault uses. Vault files are encrypted under a master key that was itself
     * unwrapped once at unlock time, so running PBKDF2 again per file would mean 210,000 hash
     * iterations to list a folder of fifty things. The header still records which case it is, so
     * a container is self-describing rather than only meaningful to whoever opened it.
     */
    private const val KDF_RAW_KEY = 0

    /**
     * OWASP's current floor for PBKDF2-HMAC-SHA256. Costs a few hundred milliseconds once per
     * archive on a phone, which is invisible next to compressing the data.
     */
    private const val ITERATIONS = 210_000

    private const val SALT_BYTES = 16
    private const val NONCE_PREFIX_BYTES = 8
    private const val NONCE_BYTES = 12
    private const val TAG_BYTES = 16
    private const val TAG_BITS = TAG_BYTES * 8
    private const val KEY_BITS = 256
    private const val HEADER_BYTES = 42
    private const val FRAME_SIZE = 64 * 1024
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    /** No identity binding: everything under one key is interchangeable. Right for a sealed
     *  archive, which is a standalone file, and wrong for a vault — see [encryptingStream]. */
    private val NO_CONTEXT = ByteArray(0)

    private val random = SecureRandom()

    /** Thrown for a wrong password and for tampering alike — the two are indistinguishable. */
    class BadPasswordOrTamperedException :
        IOException("Wrong password, or this archive has been altered")

    /**
     * Overwrites a password in place, for callers that are done with one.
     *
     * A named helper rather than Arrays.fill at each call site, because the obvious spelling of
     * the fill character is a literal NUL in the source — which git classifies the whole file as
     * binary for, losing diffs and blame on it. Writing the escape once, here, keeps that byte
     * out of the tree entirely.
     *
     * Worth being honest about the limit: this clears the array, but a password that arrived via
     * a Compose TextField already exists as an immutable String on the heap, and neither this nor
     * anything else can reach that copy before the GC does.
     */
    fun CharArray.wipe() = Arrays.fill(this, '\u0000')

    /** True if [file] starts with this container's magic. Cheap: reads 8 bytes. */
    fun isContainer(file: File): Boolean = runCatching {
        if (file.length() < HEADER_BYTES) return false
        file.inputStream().use { input ->
            val head = ByteArray(MAGIC.size)
            input.readNBytesCompat(head, MAGIC.size) == MAGIC.size && head.contentEquals(MAGIC)
        }
    }.getOrDefault(false)

    /**
     * Seals [source] into [dest]. [password] is not retained; the caller still owns it and should
     * zero it once every operation using it is done.
     */
    fun encrypt(
        source: File,
        dest: File,
        password: CharArray,
        onProgress: (deltaBytes: Long) -> Unit = {},
    ) {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val noncePrefix = ByteArray(NONCE_PREFIX_BYTES).also(random::nextBytes)
        val header = buildHeader(salt, noncePrefix)
        val key = deriveKey(password, salt, ITERATIONS)

        try {
            source.inputStream().buffered().use { input ->
                dest.outputStream().buffered().use { output ->
                    output.write(header)

                    // One Cipher, re-initialised per frame. Cipher.getInstance() per 64 KiB would
                    // be a provider lookup roughly sixteen thousand times per gigabyte.
                    val cipher = Cipher.getInstance(TRANSFORMATION)
                    val plain = ByteArray(FRAME_SIZE)
                    var index = 0
                    while (true) {
                        val read = input.readNBytesCompat(plain, FRAME_SIZE)
                        // A full frame is never the last one: when the input divides exactly by
                        // FRAME_SIZE the loop goes round once more and emits an empty final frame.
                        val last = read < FRAME_SIZE
                        cipher.initFrame(
                            Cipher.ENCRYPT_MODE, key, header, noncePrefix, index, last, NO_CONTEXT,
                        )
                        output.write(cipher.doFinal(plain, 0, read))
                        onProgress(read.toLong())
                        index++
                        if (last) break
                    }
                }
            }
        } finally {
            key.zero()
        }
    }

    /**
     * An [OutputStream] that seals everything written to it into [sink].
     *
     * This exists so compressing to an encrypted archive never writes a plaintext copy anywhere:
     * ZipOutputStream writes straight into this, so the only complete rendering of the user's
     * files is the ciphertext. The alternative — zip to a scratch file, encrypt it, delete the
     * scratch — would put a full plaintext archive in cache, on the same storage, recoverable
     * after deletion, which rather defeats the point of sealing it.
     *
     * Closing is what writes the final frame, so a caller that abandons the stream without
     * closing leaves an unopenable file. use {} handles that.
     */
    fun encryptingStream(sink: OutputStream, password: CharArray): OutputStream {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        return sealingStream(
            sink, deriveKey(password, salt, ITERATIONS), KDF_PBKDF2_SHA256, salt, NO_CONTEXT,
        )
    }

    /**
     * As [encryptingStream], but under a caller-supplied 256-bit key with no derivation step.
     *
     * [key] is copied, not retained: the caller keeps ownership and the copy is zeroed on close.
     *
     * [context] binds the ciphertext to an identity of the caller's choosing — it is mixed into
     * every frame's AAD but never stored, so opening the result requires supplying the same
     * bytes. Without it, everything sealed under one key is interchangeable with everything else
     * sealed under that key: a container proves only "someone holding the key wrote this", never
     * "this is the thing you asked for". See Vault, which is what needs this.
     */
    fun encryptingStream(
        sink: OutputStream,
        key: ByteArray,
        context: ByteArray = NO_CONTEXT,
    ): OutputStream {
        require(key.size == KEY_BITS / 8) { "Key must be ${KEY_BITS / 8} bytes" }
        return sealingStream(sink, key.copyOf(), KDF_RAW_KEY, ByteArray(SALT_BYTES), context)
    }

    private fun sealingStream(
        sink: OutputStream,
        key: ByteArray,
        kdf: Int,
        salt: ByteArray,
        context: ByteArray,
    ): OutputStream {
        val noncePrefix = ByteArray(NONCE_PREFIX_BYTES).also(random::nextBytes)
        val header = buildHeader(salt, noncePrefix, kdf)
        sink.write(header)

        return object : OutputStream() {
            private val cipher = Cipher.getInstance(TRANSFORMATION)
            private val pending = ByteArray(FRAME_SIZE)
            private var filled = 0
            private var index = 0
            private var closed = false

            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

            override fun write(b: ByteArray, off: Int, len: Int) {
                var cursor = off
                var remaining = len
                while (remaining > 0) {
                    val n = minOf(FRAME_SIZE - filled, remaining)
                    System.arraycopy(b, cursor, pending, filled, n)
                    filled += n
                    cursor += n
                    remaining -= n
                    // Sealed only once genuinely full, so the frame emitted at close is always
                    // the short one the reader requires. See the class comment.
                    if (filled == FRAME_SIZE) emit(last = false)
                }
            }

            private fun emit(last: Boolean) {
                cipher.initFrame(
                    Cipher.ENCRYPT_MODE, key, header, noncePrefix, index, last, context,
                )
                sink.write(cipher.doFinal(pending, 0, filled))
                filled = 0
                index++
            }

            override fun close() {
                if (closed) return
                closed = true
                try {
                    emit(last = true)
                    sink.flush()
                } finally {
                    key.zero()
                    sink.close()
                }
            }
        }
    }

    /**
     * An [InputStream] yielding the plaintext of a container read from [source].
     *
     * The counterpart to [encryptingStream], and the reason extraction needs no scratch file: a
     * ZipInputStream reads straight through this, so a sealed archive can be unpacked without a
     * decrypted copy of the whole thing existing anywhere.
     *
     * Authentication stays ahead of the caller. A frame's tag is verified by doFinal() before any
     * of its bytes are returned, so the most that can ever be handed out before a tamper is
     * noticed is the frames preceding the altered one — never unverified data. That is the
     * property CipherInputStream does not give you, and the reason this is hand-rolled.
     */
    fun decryptingStream(source: InputStream, password: CharArray): InputStream =
        openingStream(source, NO_CONTEXT) { parsed ->
            deriveKey(password, parsed.salt, parsed.iterations)
        }

    /**
     * As [decryptingStream], but under a caller-supplied 256-bit key with no derivation step.
     *
     * [context] must be byte-identical to what the writer sealed with, or every frame fails to
     * authenticate. That is the point: a blob filed under the wrong identity does not open.
     */
    fun decryptingStream(
        source: InputStream,
        key: ByteArray,
        context: ByteArray = NO_CONTEXT,
    ): InputStream {
        require(key.size == KEY_BITS / 8) { "Key must be ${KEY_BITS / 8} bytes" }
        return openingStream(source, context) { key.copyOf() }
    }

    private fun openingStream(
        source: InputStream,
        context: ByteArray,
        keyFor: (Header) -> ByteArray,
    ): InputStream {
        val header = ByteArray(HEADER_BYTES)
        if (source.readNBytesCompat(header, HEADER_BYTES) != HEADER_BYTES) {
            throw IOException("Not an encrypted archive")
        }
        val parsed = parseHeader(header)
        val key = keyFor(parsed)

        return object : InputStream() {
            private val cipher = Cipher.getInstance(TRANSFORMATION)
            private val sealed = ByteArray(FRAME_SIZE + TAG_BYTES)
            private var plain = ByteArray(0)
            private var pos = 0
            private var index = 0
            private var finished = false

            /** True when [plain] holds at least one unread byte. */
            private fun fill(): Boolean {
                while (pos >= plain.size) {
                    if (finished) return false
                    val read = source.readNBytesCompat(sealed, sealed.size)
                    val last = read < sealed.size
                    if (read < TAG_BYTES) throw BadPasswordOrTamperedException()
                    cipher.initFrame(
                        Cipher.DECRYPT_MODE, key, header, parsed.noncePrefix, index, last, context,
                    )
                    plain = try {
                        cipher.doFinal(sealed, 0, read)
                    } catch (e: GeneralSecurityException) {
                        throw BadPasswordOrTamperedException()
                    }
                    pos = 0
                    index++
                    if (last) finished = true
                }
                return true
            }

            override fun read(): Int =
                if (!fill()) -1 else plain[pos++].toInt() and 0xFF

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                if (!fill()) return -1
                val n = minOf(len, plain.size - pos)
                System.arraycopy(plain, pos, b, off, n)
                pos += n
                return n
            }

            override fun close() {
                key.zero()
                source.close()
            }
        }
    }

    /** Opens [source] into [dest]. Throws [BadPasswordOrTamperedException] if it does not verify. */
    fun decrypt(
        source: File,
        dest: File,
        password: CharArray,
        onProgress: (deltaBytes: Long) -> Unit = {},
    ) {
        source.inputStream().buffered().use { input ->
            val header = ByteArray(HEADER_BYTES)
            if (input.readNBytesCompat(header, HEADER_BYTES) != HEADER_BYTES) {
                throw IOException("Not an encrypted archive")
            }
            val parsed = parseHeader(header)
            // The header's own iteration count, not the current constant: raising ITERATIONS for
            // new archives must not make every existing one impossible to open. The value is
            // bounds-checked in parseHeader and authenticated by the first frame's AAD.
            val key = deriveKey(password, parsed.salt, parsed.iterations)

            try {
                dest.outputStream().buffered().use { output ->
                    val cipher = Cipher.getInstance(TRANSFORMATION)
                    val sealed = ByteArray(FRAME_SIZE + TAG_BYTES)
                    var index = 0
                    while (true) {
                        val read = input.readNBytesCompat(sealed, sealed.size)
                        val last = read < sealed.size
                        if (read < TAG_BYTES) {
                            // Every frame carries a tag, so anything shorter is a file that was
                            // cut mid-frame rather than a legitimately empty final one.
                            throw BadPasswordOrTamperedException()
                        }
                        cipher.initFrame(
                            Cipher.DECRYPT_MODE, key, header, parsed.noncePrefix, index, last,
                            NO_CONTEXT,
                        )
                        val plain = try {
                            cipher.doFinal(sealed, 0, read)
                        } catch (e: AEADBadTagException) {
                            throw BadPasswordOrTamperedException()
                        } catch (e: GeneralSecurityException) {
                            throw BadPasswordOrTamperedException()
                        }
                        output.write(plain)
                        onProgress(plain.size.toLong())
                        index++
                        if (last) break
                    }
                }
            } finally {
                key.zero()
            }
        }
    }

    // --- internals --------------------------------------------------------------

    private fun buildHeader(
        salt: ByteArray,
        noncePrefix: ByteArray,
        kdf: Int = KDF_PBKDF2_SHA256,
    ): ByteArray =
        ByteBuffer.allocate(HEADER_BYTES).apply {
            put(MAGIC)
            put(VERSION.toByte())
            put(kdf.toByte())
            putInt(if (kdf == KDF_RAW_KEY) 0 else ITERATIONS)
            put(salt)
            put(noncePrefix)
            putInt(FRAME_SIZE)
        }.array()

    private class Header(
        val iterations: Int,
        val salt: ByteArray,
        val noncePrefix: ByteArray,
    )

    private fun parseHeader(header: ByteArray): Header {
        val buffer = ByteBuffer.wrap(header)
        val magic = ByteArray(MAGIC.size).also(buffer::get)
        if (!magic.contentEquals(MAGIC)) throw IOException("Not an encrypted archive")
        val version = buffer.get().toInt()
        if (version != VERSION) throw IOException("Unsupported archive version $version")
        val kdf = buffer.get().toInt()
        if (kdf != KDF_PBKDF2_SHA256 && kdf != KDF_RAW_KEY) {
            throw IOException("Unsupported key derivation")
        }
        val iterations = buffer.int
        val salt = ByteArray(SALT_BYTES).also(buffer::get)
        val noncePrefix = ByteArray(NONCE_PREFIX_BYTES).also(buffer::get)
        val frameSize = buffer.int
        // A raw-key container has no derivation to bound; a password one must not claim zero.
        if (kdf == KDF_RAW_KEY && iterations != 0) throw IOException("Malformed header")
        // Not defensive noise: these bounds run before any PBKDF2 work, so a header claiming two
        // billion iterations or a gigabyte frame cannot be used to hang the app. The values are
        // authenticated by the first frame's AAD, but that check comes after they have been used.
        if (kdf == KDF_PBKDF2_SHA256 && iterations !in 1..MAX_ITERATIONS) {
            throw IOException("Implausible key derivation cost")
        }
        if (frameSize != FRAME_SIZE) throw IOException("Unsupported frame size")
        return Header(iterations, salt, noncePrefix)
    }

    /**
     * Sets up one frame. The nonce is what makes position tamper-evident *within* a file; the
     * AAD is the redundant second layer described in the class comment, plus [context], which is
     * the one part of it that is not redundant. Read the class comment before changing either.
     */
    private fun Cipher.initFrame(
        mode: Int,
        key: ByteArray,
        header: ByteArray,
        noncePrefix: ByteArray,
        index: Int,
        last: Boolean,
        context: ByteArray,
    ) {
        // Nonce is prefix || index, so it is unique per frame within a file and, because the
        // prefix is fresh random per file, unique across files under the same key too. GCM's
        // catastrophic failure mode is nonce reuse; nothing here can produce one.
        val nonce = ByteBuffer.allocate(NONCE_BYTES)
            .put(noncePrefix)
            .putInt(index)
            .array()
        // Length-prefixed, so a context of "ab" || "c" cannot collide with "a" || "bc". Concatenating
        // variable-length fields into an AAD without one is the classic way to make two different
        // identities produce the same authenticated bytes.
        val aad = ByteBuffer.allocate(header.size + 4 + context.size + 4 + 1)
            .put(header)
            .putInt(context.size)
            .put(context)
            .putInt(index)
            .put(if (last) 1 else 0)
            .array()
        init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        updateAAD(aad)
    }

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            // Clears the copy PBEKeySpec made of the password; the caller's array is theirs.
            spec.clearPassword()
        }
    }

    private fun ByteArray.zero() = Arrays.fill(this, 0)

    /**
     * InputStream.read is allowed to return fewer bytes than asked for at any time, not only at
     * end of stream. Reading a frame short would change where a frame boundary falls and make a
     * perfectly good archive fail to verify, so fill the buffer properly.
     *
     * readNBytes exists on the JDK but not on every Android release this could be built against,
     * so the loop is spelled out.
     */
    private fun InputStream.readNBytesCompat(into: ByteArray, count: Int): Int {
        var total = 0
        while (total < count) {
            val n = read(into, total, count - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    private const val MAX_ITERATIONS = 10_000_000
}
