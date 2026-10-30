package app.halum.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

class ModelStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeSource(
        private val assets: Map<String, ByteArray>,
        private val manifest: String?,
    ) : ModelStore.Source {
        var opened = 0

        override fun open(assetPath: String): InputStream? {
            opened++
            val data = assets[assetPath] ?: return null
            return ByteArrayInputStream(data)
        }

        override fun readManifest(): String? = manifest
    }

    private fun manifest(vararg entries: String) = entries.joinToString("\n", postfix = "\n")

    @Test
    fun `copies the asset and records its hash`() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val sha = ModelStore.sha256Of(bytes)
        val cache = tmp.newFolder("models")
        val store =
            ModelStore(
                FileCache(cache),
                FakeSource(mapOf("models/a.tflite" to bytes), manifest("$sha  4  models/a.tflite")),
            )

        val model = store.obtain("a.tflite")!!

        assertTrue(model.file.isFile)
        assertEquals(4L, model.size)
        assertEquals(sha, model.sha256)
        assertEquals(listOf(1, 2, 3, 4), model.file.readBytes().toList())
    }

    @Test
    fun `a second call reuses the copied file`() {
        val bytes = byteArrayOf(9, 9)
        val sha = ModelStore.sha256Of(bytes)
        val source = FakeSource(mapOf("models/a.tflite" to bytes), manifest("$sha  2  models/a.tflite"))
        val store = ModelStore(FileCache(tmp.newFolder("models")), source)

        store.obtain("a.tflite")
        val openedAfterFirst = source.opened
        val again = store.obtain("a.tflite")

        assertEquals("identical file is not re-copied", openedAfterFirst, source.opened)
        assertEquals(sha, again!!.sha256)
    }

    @Test
    fun `a corrupted cache file is replaced`() {
        val bytes = byteArrayOf(5, 6, 7)
        val sha = ModelStore.sha256Of(bytes)
        val cache = tmp.newFolder("models")
        File(cache, "a.tflite").writeBytes(byteArrayOf(0, 0))
        val store =
            ModelStore(
                FileCache(cache),
                FakeSource(mapOf("models/a.tflite" to bytes), manifest("$sha  3  models/a.tflite")),
            )

        val model = store.obtain("a.tflite")!!

        assertEquals(bytes.toList(), model.file.readBytes().toList())
    }

    @Test
    fun `a missing asset is null, not an exception`() {
        val store = ModelStore(FileCache(tmp.newFolder("models")), FakeSource(emptyMap(), null))
        assertNull(store.obtain("migan.tflite"))
    }

    @Test
    fun `a hash mismatch fails loudly instead of shipping the wrong model`() {
        val bytes = byteArrayOf(1, 2, 3)
        val store =
            ModelStore(
                FileCache(tmp.newFolder("models")),
                FakeSource(
                    mapOf("models/a.tflite" to bytes),
                    manifest("${"0".repeat(64)}  3  models/a.tflite"),
                ),
            )

        val ex = runCatching { store.obtain("a.tflite") }.exceptionOrNull()

        assertTrue("expected ModelException, got $ex", ex is ModelStore.ModelException)
        assertTrue("partial download is not left behind", !File(tmp.root, "models/a.tflite").exists())
    }

    @Test
    fun `an asset without a manifest entry is copied with its own hash`() {
        val bytes = byteArrayOf(4, 4, 4)
        val store = ModelStore(FileCache(tmp.newFolder("models")), FakeSource(mapOf("models/a.tflite" to bytes), null))

        val model = store.obtain("a.tflite")!!

        assertEquals(ModelStore.sha256Of(bytes), model.sha256)
    }
}
