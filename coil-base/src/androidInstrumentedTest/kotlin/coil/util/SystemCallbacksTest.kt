package coil.util

import android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
import android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE
import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import androidx.test.core.app.ApplicationProvider
import coil.ImageLoader
import coil.RealImageLoader
import coil.asCoilImage
import coil.memory.MemoryCache
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test

class SystemCallbacksTest {

    private lateinit var context: Context

    @Before
    fun before() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun imageLoaderIsFreedWithoutShutdown() {
        val options = (ImageLoader(context) as RealImageLoader).options
        val systemCallbacks = SystemCallbacks(options) as AndroidSystemCallbacks

        val bitmaps = mutableListOf<Bitmap>()
        while (systemCallbacks.imageLoader?.get() != null) {
            // Request that garbage collection occur.
            Runtime.getRuntime().gc()

            // Keep allocating bitmaps until either the image loader is freed or we run out of memory.
            bitmaps += createBitmap(500, 500)
        }
        bitmaps.clear()

        // Ensure that the next system callback is called.
        systemCallbacks.onTrimMemory(TRIM_MEMORY_BACKGROUND)

        assertTrue(systemCallbacks.isShutdown)
    }

    @Test
    fun trimMemoryCallsArePassedThrough() {
        val memoryCache = MemoryCache.Builder()
            .maxSizePercent(context)
            .build()
        val options = (ImageLoader(context) as RealImageLoader).options
        val systemCallbacks = SystemCallbacks(options) as AndroidSystemCallbacks

        memoryCache[MemoryCache.Key("1")] = MemoryCache.Value(
            image = createBitmap(1000, 1000, Bitmap.Config.ARGB_8888)
                .toDrawable(context).asCoilImage()
        )
        memoryCache[MemoryCache.Key("2")] = MemoryCache.Value(
            image = createBitmap(1000, 1000, Bitmap.Config.ARGB_8888)
                .toDrawable(context).asCoilImage()
        )

        assertEquals(8_000_000, memoryCache.size)

        systemCallbacks.onTrimMemory(TRIM_MEMORY_COMPLETE)

        assertEquals(0, memoryCache.size)
    }
}