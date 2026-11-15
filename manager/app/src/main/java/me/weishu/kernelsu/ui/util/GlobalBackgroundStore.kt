package me.weishu.kernelsu.ui.util

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.math.roundToInt

data class GlobalBackgroundSettingsState(
    val image: String = "",
    val opacity: Int = 100,
    val readability: Int = 65,
)

/** Independent of WallpaperStore, theme packages, and all local/card reset operations. */
class GlobalBackgroundStore private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("global_background", Context.MODE_PRIVATE)
    private val directory = File(appContext.filesDir, "global_background")
    private val mutex = Mutex()
    private val mutableSettings = MutableStateFlow(readSettings())
    val settings: StateFlow<GlobalBackgroundSettingsState> = mutableSettings.asStateFlow()
    // Keep a strong reference: SharedPreferences retains listeners weakly.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        mutableSettings.value = readSettings()
    }

    init {
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    private fun readSettings() = GlobalBackgroundSettingsState(
        image = preferences.getString("image", "").orEmpty().takeIf { validName(it) }.orEmpty(),
        opacity = preferences.getInt("opacity", 100).coerceIn(0, 100),
        readability = preferences.getInt("readability", 65).coerceIn(0, 100),
    )

    suspend fun importImage(uri: Uri) = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(directory.isDirectory || directory.mkdirs()) { "Could not create background directory" }
            val source = File.createTempFile("import-", ".tmp", directory)
            val target = File(directory, "${UUID.randomUUID()}.png")
            var bitmap: Bitmap? = null
            var published = false
            try {
                appContext.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Could not open image" }
                    source.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= MAX_IMPORT_BYTES) { "Image exceeds 32 MB" }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                val decoded = decodeSource(source, 2048)
                bitmap = decoded
                currentCoroutineContext().ensureActive()
                target.outputStream().use { output ->
                    check(decoded.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Could not encode image" }
                }
                currentCoroutineContext().ensureActive()
                // Publish only a complete file. Once committed, cancellation must not remove it.
                withContext(NonCancellable) {
                    check(preferences.edit().putString("image", target.name).commit()) { "Could not save background" }
                    published = true
                    mutableSettings.value = readSettings()
                    prune(target.name)
                }
            } catch (error: OutOfMemoryError) {
                throw IOException("Not enough memory to import image", error)
            } finally {
                bitmap?.recycle()
                source.delete()
                if (!published) target.delete()
            }
        }
    }

    suspend fun reset() = withContext(Dispatchers.IO) {
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                check(preferences.edit().clear().commit()) { "Could not reset background" }
                mutableSettings.value = readSettings()
                prune("")
            }
        }
    }

    suspend fun setOpacity(value: Int) = setPercent("opacity", value)
    suspend fun setReadability(value: Int) = setPercent("readability", value)

    private suspend fun setPercent(key: String, value: Int) = withContext(Dispatchers.IO) {
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            check(preferences.edit().putInt(key, value.coerceIn(0, 100)).commit()) { "Could not save background settings" }
            mutableSettings.value = readSettings()
        }
    }

    /** Native decode cannot be interrupted; recycle its result if cancellation arrived meanwhile. */
    suspend fun decode(name: String, maxEdge: Int = 2048): Bitmap? {
        var bitmap: Bitmap? = null
        try {
            return withContext(Dispatchers.IO) {
                if (!validName(name)) return@withContext null
                val file = File(directory, name)
                currentCoroutineContext().ensureActive()
                if (file.isFile) bitmap = decodeSource(file, maxEdge)
                currentCoroutineContext().ensureActive()
                bitmap
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            // Also covers prompt cancellation while dispatching the IO result to the caller.
            bitmap?.recycle()
            throw error
        } catch (_: IOException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        } catch (_: SecurityException) {
            return null
        } catch (_: OutOfMemoryError) {
            return null
        }
    }

    private fun decodeSource(file: File, maxEdge: Int): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > maxEdge) {
                val scale = maxEdge.toFloat() / longest
                decoder.setTargetSize(
                    (info.size.width * scale).roundToInt().coerceAtLeast(1),
                    (info.size.height * scale).roundToInt().coerceAtLeast(1),
                )
            }
        }

    private fun prune(keep: String) {
        directory.listFiles()?.forEach { file ->
            if (file.name != keep && (validName(file.name) || file.name.startsWith("import-"))) file.delete()
        }
    }

    companion object {
        private const val MAX_IMPORT_BYTES = 32L * 1024 * 1024
        private val IMAGE_NAME = Regex("[0-9a-fA-F-]{36}\\.png")
        private fun validName(name: String) = IMAGE_NAME.matches(name)
        @Volatile private var instance: GlobalBackgroundStore? = null

        fun get(context: Context): GlobalBackgroundStore = instance ?: synchronized(this) {
            instance ?: GlobalBackgroundStore(context).also { instance = it }
        }
    }
}
