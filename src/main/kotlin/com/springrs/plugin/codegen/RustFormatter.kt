package com.springrs.plugin.codegen

import com.intellij.openapi.diagnostic.logger
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Formats generated Rust files using `rustfmt`.
 *
 * If `rustfmt` is not available on PATH, formatting is silently skipped.
 */
object RustFormatter {

    private val LOG = logger<RustFormatter>()

    /**
     * Format a list of `.rs` files using `rustfmt`.
     *
     * @param files Files to format
     * @param timeoutSeconds Timeout per file
     * @return Number of files successfully formatted
     */
    fun formatFiles(files: List<File>, timeoutSeconds: Long = 5): Int {
        if (files.isEmpty()) return 0
        if (!isRustfmtAvailable()) {
            LOG.info("rustfmt not found on PATH, skipping formatting")
            return 0
        }

        var formatted = 0
        for (file in files) {
            if (!file.name.endsWith(".rs")) continue
            try {
                val process = ProcessBuilder("rustfmt", file.absolutePath)
                    .redirectErrorStream(true)
                    .start()
                val success = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
                if (success && process.exitValue() == 0) {
                    formatted++
                } else {
                    val output = process.inputStream.bufferedReader().readText()
                    LOG.debug("rustfmt failed for ${file.name}: $output")
                }
            } catch (ex: Exception) {
                LOG.debug("rustfmt error for ${file.name}", ex)
            }
        }
        return formatted
    }

    /**
     * Check if `rustfmt` is available on the system PATH.
     */
    private fun isRustfmtAvailable(): Boolean {
        return try {
            val process = ProcessBuilder("rustfmt", "--version")
                .redirectErrorStream(true)
                .start()
            process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }
}
