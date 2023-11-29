package coil.request

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.lifecycle.Lifecycle
import coil.Extras
import coil.ImageLoader
import coil.drawable
import coil.memory.MemoryCache
import coil.size.Dimension
import coil.size.Scale
import coil.size.Size
import coil.target.Target
import coil.target.ViewTarget
import coil.util.HardwareBitmapService
import coil.util.Logger
import coil.util.SystemCallbacks
import coil.util.VALID_TRANSFORMATION_CONFIGS
import coil.util.allowInexactSize
import coil.util.getLifecycle
import coil.util.isHardware
import coil.util.safeConfig
import kotlinx.coroutines.Job

internal actual fun RequestService(
    imageLoader: ImageLoader,
    systemCallbacks: SystemCallbacks,
    logger: Logger?,
): RequestService = AndroidRequestService(imageLoader, systemCallbacks, logger)

/** Handles operations that act on [ImageRequest]s. */
internal class AndroidRequestService(
    private val imageLoader: ImageLoader,
    private val systemCallbacks: SystemCallbacks,
    logger: Logger?,
): RequestService {
    private val hardwareBitmapService = HardwareBitmapService(logger)

    /**
     * Wrap [request] to automatically dispose and/or restart the [ImageRequest]
     * based on its lifecycle.
     */
    override fun requestDelegate(request: ImageRequest, job: Job): RequestDelegate {
        val lifecycle = request.resolveLifecycle()
        val target = request.target
        if (target is ViewTarget<*>) {
            return ViewTargetRequestDelegate(imageLoader, request, target, lifecycle, job)
        } else {
            return BaseRequestDelegate(lifecycle, job)
        }
    }

    private fun ImageRequest.resolveLifecycle(): Lifecycle {
        val target = target
        val context = if (target is ViewTarget<*>) target.view.context else context
        return context.getLifecycle() ?: GlobalLifecycle
    }

    override fun errorResult(request: ImageRequest, throwable: Throwable): ErrorResult {
        return commonErrorResult(request, throwable)
    }

    override fun options(request: ImageRequest, size: Size): Options {
        return Options(
            context = request.context,
            size = size,
            scale = request.resolveScale(size),
            allowInexactSize = request.allowInexactSize,
            diskCacheKey = request.diskCacheKey,
            memoryCachePolicy = request.memoryCachePolicy,
            diskCachePolicy = request.diskCachePolicy,
            networkCachePolicy = request.resolveNetworkCachePolicy(),
            extras = request.resolveExtras(size),
        )
    }

    private fun ImageRequest.resolveScale(size: Size): Scale {
        // Use `Scale.FIT` if either dimension is undefined.
        if (size.width == Dimension.Undefined || size.height == Dimension.Undefined) {
            return Scale.FIT
        } else {
            return scale
        }
    }

    private fun ImageRequest.resolveNetworkCachePolicy(): CachePolicy {
        // Disable fetching from the network if we know we're offline.
        if (systemCallbacks.isOnline) {
            return networkCachePolicy
        } else {
            return CachePolicy.DISABLED
        }
    }

    private fun ImageRequest.resolveExtras(size: Size): Extras {
        var bitmapConfig = bitmapConfig
        var allowRgb565 = allowRgb565

        // Fall back to ARGB_8888 if the requested bitmap config does not pass the checks.
        if (!isBitmapConfigValidMainThread(this, size)) {
            bitmapConfig = Bitmap.Config.ARGB_8888
        }

        // Disable allowRgb565 if there are transformations or the requested config is ALPHA_8.
        // ALPHA_8 is a mask config where each pixel is 1 byte so it wouldn't make sense to use
        // RGB_565 as an optimization in that case.
        allowRgb565 = allowRgb565 &&
            transformations.isEmpty() &&
            bitmapConfig != Bitmap.Config.ALPHA_8

        var builder: Extras.Builder? = null
        if (bitmapConfig != this.bitmapConfig) {
            builder = extras.newBuilder().set(Extras.Key.bitmapConfig, bitmapConfig)
        }
        if (allowRgb565 != this.allowRgb565) {
            builder = (builder ?: extras.newBuilder()).set(Extras.Key.allowRgb565, allowRgb565)
        }
        return builder?.build() ?: extras
    }

    override fun updateOptionsOnWorkerThread(options: Options): Options {
        if (!isBitmapConfigValidWorkerThread(options)) {
            return options.copy(
                extras = options.extras.newBuilder()
                    .set(Extras.Key.bitmapConfig, Bitmap.Config.ARGB_8888)
                    .build(),
            )
        } else {
            return options
        }
    }

    /**
     * Return 'true' if [cacheValue] is a valid (i.e. can be returned to its [Target])
     * config for [request].
     */
    override fun isCacheValueValidForHardware(
        request: ImageRequest,
        cacheValue: MemoryCache.Value,
    ): Boolean {
        val drawable = cacheValue.image.drawable as? BitmapDrawable ?: return true
        val requestedConfig = drawable.bitmap.safeConfig
        return isConfigValidForHardware(request, requestedConfig)
    }

    /**
     * Return 'true' if [requestedConfig] is a valid (i.e. can be returned to its [Target])
     * config for [request].
     */
    private fun isConfigValidForHardware(
        request: ImageRequest,
        requestedConfig: Bitmap.Config,
    ): Boolean {
        // Short circuit if the requested bitmap config is software.
        if (!requestedConfig.isHardware) {
            return true
        }

        // Ensure the request allows hardware bitmaps.
        if (!request.allowHardware) {
            return false
        }

        // Prevent hardware bitmaps for non-hardware accelerated targets.
        val target = request.target
        if (target is ViewTarget<*> &&
            target.view.run { isAttachedToWindow && !isHardwareAccelerated }) {
            return false
        }

        return true
    }

    /** Return 'true' if the current bitmap config is valid, else use [Bitmap.Config.ARGB_8888]. */
    fun isBitmapConfigValidMainThread(
        request: ImageRequest,
        size: Size,
    ): Boolean {
        val validForTransformations = request.transformations.isEmpty() ||
            request.bitmapConfig in VALID_TRANSFORMATION_CONFIGS
        val validForHardware = !request.bitmapConfig.isHardware ||
            (isConfigValidForHardware(request, request.bitmapConfig) &&
                hardwareBitmapService.allowHardwareMainThread(size))
        return validForTransformations && validForHardware
    }

    /** Return 'true' if the current bitmap config is valid, else use [Bitmap.Config.ARGB_8888]. */
    fun isBitmapConfigValidWorkerThread(
        options: Options,
    ): Boolean {
        return !options.bitmapConfig.isHardware || hardwareBitmapService.allowHardwareWorkerThread()
    }
}