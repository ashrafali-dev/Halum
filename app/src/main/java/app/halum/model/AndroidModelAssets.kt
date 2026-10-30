package app.halum.model

import android.content.Context
import java.io.File
import java.io.InputStream

/** [ModelStore.Source] over `assets/models/` + the Gradle-generated manifest. */
class AssetSource(
    private val context: Context,
) : ModelStore.Source {
    override fun open(assetPath: String): InputStream? = runCatching { context.assets.open(assetPath) }.getOrNull()

    override fun readManifest(): String? =
        runCatching {
            context.assets
                .open(MANIFEST)
                .bufferedReader()
                .use { it.readText() }
        }.getOrNull()

    private companion object {
        // Written by `modelsManifest` in app/build.gradle.kts.
        const val MANIFEST = "models/MODELS.sha256"
    }
}

/** [ModelStore.Cache] in `filesDir/models/`, one directory per manifest hash. */
class FileCache(
    private val root: File,
) : ModelStore.Cache {
    override fun file(name: String): File = File(root, name)
}

/** D14.13: one store per process; models are loaded lazily by the engines. */
object Models {
    @Volatile
    private var store: ModelStore? = null

    fun store(context: Context): ModelStore {
        store?.let { return it }
        return synchronized(this) {
            store ?: ModelStore(
                cache = FileCache(File(context.filesDir, "models")),
                source = AssetSource(context),
            ).also { store = it }
        }
    }

    const val BUBBLE = "bubble.tflite"
    const val MIGAN = "migan.tflite"
}
