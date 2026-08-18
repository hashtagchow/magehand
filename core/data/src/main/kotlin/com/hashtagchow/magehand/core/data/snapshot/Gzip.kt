package com.hashtagchow.magehand.core.data.snapshot

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * gzip for creature snapshots.
 *
 * 06-offline-and-sync.md §Data budget: a snapshot is ≈1 MB raw and ~100 KB gzipped, and
 * we keep up to ten per account — so the compression is the difference between ~1 MB and
 * ~10 MB of user storage, not a micro-optimisation.
 *
 * `java.util.zip` rather than a dependency: it is in the JDK and on Android, and this is
 * the whole of the requirement.
 */
internal object Gzip {

    /** Default buffer for the inflate path; a snapshot is ~1 MB, so start there. */
    private const val INITIAL_BUFFER = 1 shl 20

    fun deflate(text: String): ByteArray {
        val out = ByteArrayOutputStream(1 shl 17)
        GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return out.toByteArray()
    }

    fun inflate(bytes: ByteArray): String =
        GZIPInputStream(bytes.inputStream()).use { input ->
            val out = ByteArrayOutputStream(INITIAL_BUFFER)
            input.copyTo(out, DEFAULT_BUFFER_SIZE)
            out.toString(Charsets.UTF_8.name())
        }
}
