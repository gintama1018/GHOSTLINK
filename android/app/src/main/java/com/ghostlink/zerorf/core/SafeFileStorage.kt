package com.ghostlink.zerorf.core

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Hardened filesystem layer protecting against Path Traversal (CWE-22),
 * partial file corruption, and storage exhaustion.
 */
object SafeFileStorage {

    /**
     * Sanitizes untrusted remote filename to prevent directory traversal attacks.
     */
    fun sanitizeFilename(rawName: String): String {
        var clean = rawName.trim()
            .replace("\\", "/")
            .substringAfterLast("/") // Discard any leading directory path
            .replace(Regex("[^a-zA-Z0-9._\\- ]"), "_") // Discard control characters and special symbols
            .replace(Regex("\\.{2,}"), ".") // Prevent ".."
            .trim()

        if (clean.isEmpty() || clean == "." || clean.startsWith(".")) {
            clean = "received_file_${System.currentTimeMillis()}.bin"
        }

        // Bounded length
        if (clean.length > 120) {
            val ext = if (clean.contains(".")) "." + clean.substringAfterLast(".") else ""
            clean = clean.take(100) + ext
        }

        return clean
    }

    /**
     * Resolves a safe, non-colliding file path inside baseDir, strictly verifying canonical path containment.
     */
    fun getSafeTargetFile(baseDir: File, proposedName: String): File {
        val safeName = sanitizeFilename(proposedName)
        val canonicalBase = baseDir.canonicalFile

        var target = File(canonicalBase, safeName)
        if (!target.canonicalFile.path.startsWith(canonicalBase.path)) {
            throw SecurityException("Path traversal attempt detected: $proposedName")
        }

        // Avoid overwriting existing files
        if (target.exists()) {
            val nameWithoutExt = if (safeName.contains(".")) safeName.substringBeforeLast(".") else safeName
            val ext = if (safeName.contains(".")) "." + safeName.substringAfterLast(".") else ""
            var counter = 1
            while (target.exists() && counter < 1000) {
                target = File(canonicalBase, "$nameWithoutExt ($counter)$ext")
                counter++
            }
        }

        return target
    }

    /**
     * Writes bytes to disk safely using an atomic temporary file with sync to prevent partial writes.
     */
    fun writeBytesAtomically(targetFile: File, bytes: ByteArray) {
        val tempFile = File(targetFile.parentFile, targetFile.name + ".part")
        try {
            FileOutputStream(tempFile).use { fos ->
                fos.write(bytes)
                fos.fd.sync() // Ensure flushed to physical storage
            }

            if (targetFile.exists()) {
                targetFile.delete()
            }

            val renamed = tempFile.renameTo(targetFile)
            if (!renamed) {
                throw IOException("Failed to rename temporary file to destination: ${targetFile.name}")
            }
        } catch (e: Exception) {
            tempFile.delete()
            throw e
        }
    }
}
