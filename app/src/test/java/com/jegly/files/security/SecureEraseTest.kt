package com.jegly.files.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SecureEraseTest {

    @get:Rule
    val temp = TemporaryFolder()

    /**
     * The overwrite is asserted on its own because a delete leaves nothing to inspect: once the
     * file is unlinked there is no supported way to read the blocks back and prove anything about
     * them. What is testable is that the bytes on the file are gone and its length is unchanged,
     * so the unlink that follows returns overwritten space rather than the original contents.
     */
    @Test
    fun `overwriting replaces a file's bytes and keeps its length`() {
        val file = File(temp.root, "secret.txt")
        val plaintext = "the account number is 5556667778889990"
        file.writeText(plaintext)

        SecureErase.overwrite(file)

        assertEquals(plaintext.length.toLong(), file.length())
        assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains("5556667778889990"))
    }

    /** Larger than one buffer, so the chunked write loop is covered rather than a single pass. */
    @Test
    fun `overwriting covers a file larger than the buffer`() {
        val file = File(temp.root, "big.bin")
        val marker = "NEEDLE"
        file.writeText(marker.repeat(120_000))
        val length = file.length()

        SecureErase.overwrite(file)

        assertEquals(length, file.length())
        assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains(marker))
    }

    @Test
    fun `an empty file is left alone rather than failing`() {
        val file = File(temp.root, "empty.txt").apply { createNewFile() }

        SecureErase.overwrite(file)

        assertEquals(0L, file.length())
    }
}
