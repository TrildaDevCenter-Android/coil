package coil.fetch

import android.content.ContentResolver.SCHEME_FILE
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import coil.ImageLoader
import coil.Uri
import coil.request.Options
import coil.toUri
import coil.util.ASSET_FILE_PATH_ROOT
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class AssetUriFetcherTest {

    private lateinit var context: Context
    private lateinit var fetcherFactory: AssetUriFetcher.Factory

    @Before
    fun before() {
        context = ApplicationProvider.getApplicationContext()
        fetcherFactory = AssetUriFetcher.Factory()
    }

    @Test
    fun basic() = runTest {
        val uri = "$SCHEME_FILE:///$ASSET_FILE_PATH_ROOT/normal.jpg".toUri()
        assertUriFetchesCorrectly(uri)
    }

    @Test
    fun nestedPath() = runTest {
        val uri = "$SCHEME_FILE:///$ASSET_FILE_PATH_ROOT/exif/large_metadata.jpg".toUri()
        assertUriFetchesCorrectly(uri)
    }

    private suspend fun assertUriFetchesCorrectly(uri: Uri) {
        val result = assertNotNull(
            fetcherFactory.create(
                data = uri,
                options = Options(context),
                imageLoader = ImageLoader(context)
            )
        ).fetch()

        assertIs<SourceFetchResult>(result)
        assertEquals("image/jpeg", result.mimeType)
        assertFalse(result.source.source().exhausted())
    }
}