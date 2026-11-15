package shura.home.helpers

import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.collection.LruCache
import shura.home.models.AppLauncher

object IconCache {
    private val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSizeKb = (maxMemoryKb / 8).coerceIn(16 * 1024, 64 * 1024)

    private val drawableCache = object : LruCache<String, Drawable>(cacheSizeKb) {
        override fun sizeOf(key: String, value: Drawable): Int {
            return when (value) {
                is BitmapDrawable -> (value.bitmap.byteCount / 1024).coerceAtLeast(1)
                else -> 128
            }
        }
    }

    @Volatile
    private var cachedLaunchers = emptyList<AppLauncher>()

    var launchers: List<AppLauncher>
        get() = cachedLaunchers
        set(value) {
            synchronized(this) {
                cachedLaunchers = value
            }
        }

    fun getDrawable(key: String): Drawable? = synchronized(drawableCache) {
        drawableCache.get(key)
    }

    fun putDrawable(key: String, drawable: Drawable) = synchronized(drawableCache) {
        drawableCache.put(key, drawable)
    }

    fun removeDrawable(key: String) = synchronized(drawableCache) {
        drawableCache.remove(key)
    }

    fun clear() {
        launchers = emptyList()
        synchronized(drawableCache) {
            drawableCache.evictAll()
        }
    }
}
