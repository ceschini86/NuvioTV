package com.nuvio.tv.core.diagnostics

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream

/**
 * Appends lines to `<dir>/<baseName>` and flushes each one. When the file would pass
 * [maxBytes], it becomes `<baseName>.1`, the old `.1` becomes `.2`, and so on; the file past
 * [keep] is deleted. A higher number is an older file, the same naming logcat `-r/-n` uses.
 */
internal class RotatingLineWriter(
    private val dir: File,
    private val baseName: String,
    private val maxBytes: Long,
    private val keep: Int,
) : Closeable {
    private val current = File(dir, baseName)
    private var out = FileOutputStream(current, true)
    private var size = current.length()

    fun writeLine(line: String) {
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        if (size > 0 && size + bytes.size > maxBytes) rotate()
        out.write(bytes)
        out.flush()
        size += bytes.size
    }

    private fun rotate() {
        out.close()
        File(dir, "$baseName.$keep").delete()
        for (index in keep - 1 downTo 1) {
            val from = File(dir, "$baseName.$index")
            if (from.exists()) from.renameTo(File(dir, "$baseName.${index + 1}"))
        }
        current.renameTo(File(dir, "$baseName.1"))
        out = FileOutputStream(current, false)
        size = 0
    }

    override fun close() = out.close()
}
