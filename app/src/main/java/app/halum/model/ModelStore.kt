package app.halum.model

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * D14.13: model files are `noCompress` assets, copied once into a private
 * directory and memory-mapped from there. The asset system cannot be mmapped
 * inside the APK zip, and deflating a 12 MB model is exactly the transient
 * allocation that OOMs a 192 MB heap.
 *
 * The store is Android-free: [Source] supplies the bytes, [Cache] holds them,
 * so the copy/version logic is unit-tested in pure JVM and the Android
 * binding is a thin adapter in [AndroidModelAssets].
 */
class ModelStore(
    private val cache: Cache,
    private val source: Source,
) {
    interface Source {
        /** Null when the asset is not present in this build. */
        fun open(assetPath: String): InputStream?

        /** sha256 -> name, as written by the Gradle `modelsManifest` task. */
        fun readManifest(): String?
    }

    interface Cache {
        fun file(name: String): File

        fun bytesOf(file: File): ByteArray? = null
    }

    data class Model(
        val file: File,
        val sha256: String,
        val size: Long,
    )

    /**
     * Copies `models/$name` into the cache unless an identical file is already
     * there. Returns null when the build has no such asset, so callers can
     * fall back (no MI-GAN -> solid fill) instead of crashing.
     */
    fun obtain(name: String): Model? {
        val expected = expectedSha256(name)
        val target = cache.file(name)
        if (target.isFile && expected != null && sha256Of(target) == expected) {
            return Model(target, expected, target.length())
        }
        val stream = source.open("models/$name") ?: return null
        stream.use { input ->
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "$name.download")
            tmp.outputStream().use { out -> input.copyTo(out) }
            val digest = sha256Of(tmp)
            if (expected != null && digest != expected) {
                tmp.delete()
                throw ModelException("$name sha256 $digest != manifest $expected")
            }
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) {
                    tmp.delete()
                    throw ModelException("could not move $name into ${target.parentFile}")
                }
            }
            return Model(target, digest, target.length())
        }
    }

    /** Null when the manifest has no entry — an unversioned asset is allowed. */
    private fun expectedSha256(name: String): String? {
        val manifest = source.readManifest() ?: return null
        for (line in manifest.lineSequence()) {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size >= 3 && parts[2] == "models/$name") return parts[0]
        }
        return null
    }

    class ModelException(
        message: String,
    ) : RuntimeException(message)

    companion object {
        fun sha256Of(file: File): String = file.inputStream().use { sha256Of(it) }

        fun sha256Of(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        fun sha256Of(input: InputStream): String {
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
