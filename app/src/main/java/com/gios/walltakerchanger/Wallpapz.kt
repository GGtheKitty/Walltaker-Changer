package com.gios.walltakerchanger

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import androidx.preference.PreferenceManager
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class Wallpapz : WallpaperService() {
    override fun onCreateEngine(): Engine {
        return WallpaperEngine()
    }

    private inner class WallpaperEngine : Engine(), Drawable.Callback {
        private val handler = Handler(Looper.getMainLooper())
        private var visible = false
        private var surfaceReady = false
        private var destroyed = false
        private var width = 0
        private var height = 0
        private var requestedUrl: String? = null
        private var drawable: Drawable? = null
        private var target: CustomTarget<Drawable>? = null
        private var retryAt = 0L
        private val preferences = PreferenceManager.getDefaultSharedPreferences(this@Wallpapz)

        private val frame = object : Runnable {
            override fun run() {
                if (!visible || !surfaceReady || destroyed) return
                val url = liveUrl?.takeIf { it.isNotBlank() }
                    ?: preferences.getString("lastLiveWallpaperUrl", null)
                if (!url.isNullOrBlank() &&
                    (url != requestedUrl || target == null && android.os.SystemClock.uptimeMillis() >= retryAt)) {
                    loadWallpaper(url)
                }
                drawWallpaper()
                handler.postDelayed(this, 33)
            }
        }

        private fun loadWallpaper(url: String) {
            requestedUrl = url
            clearImage()
            val imageTarget = object : CustomTarget<Drawable>(max(width, 1), max(height, 1)) {
                override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                    if (destroyed || target !== this) return
                    drawable = resource
                    resource.callback = this@WallpaperEngine
                    if (visible) (resource as? Animatable)?.start()
                    drawWallpaper()
                }

                override fun onLoadFailed(errorDrawable: Drawable?) {
                    Log.w("WalltakerChanger", "Unable to load live wallpaper: $url")
                    handler.post {
                        if (target === this) {
                            clearImage()
                            retryAt = android.os.SystemClock.uptimeMillis() + 10_000L
                        }
                    }
                }

                override fun onLoadCleared(placeholder: Drawable?) {
                    if (target === this) releaseDrawable()
                }
            }
            target = imageTarget
            // Hardware bitmaps cannot be drawn onto SurfaceHolder.lockCanvas()'s software canvas.
            Glide.with(applicationContext).load(url).disallowHardwareConfig().fitCenter().into(imageTarget)
        }

        private fun drawWallpaper() {
            if (!surfaceReady || destroyed) return
            var canvas: Canvas? = null
            try {
                val drawingCanvas = surfaceHolder.lockCanvas() ?: return
                canvas = drawingCanvas
                drawingCanvas.drawColor(Color.BLACK)
                drawable?.let { image ->
                    val imageWidth = max(image.intrinsicWidth, 1)
                    val imageHeight = max(image.intrinsicHeight, 1)
                    val scaleX = drawingCanvas.width.toFloat() / imageWidth
                    val scaleY = drawingCanvas.height.toFloat() / imageHeight
                    val scale = if (preferences.getBoolean("iFitLive", false)) {
                        min(scaleX, scaleY)
                    } else {
                        max(scaleX, scaleY)
                    }
                    val drawWidth = (imageWidth * scale).roundToInt()
                    val drawHeight = (imageHeight * scale).roundToInt()
                    val left = (drawingCanvas.width - drawWidth) / 2
                    val top = (drawingCanvas.height - drawHeight) / 2
                    image.setBounds(left, top, left + drawWidth, top + drawHeight)
                    image.draw(drawingCanvas)
                }
            } catch (e: Exception) {
                Log.w("WalltakerChanger", "Unable to draw live wallpaper", e)
            } finally {
                canvas?.let {
                    try {
                        surfaceHolder.unlockCanvasAndPost(it)
                    } catch (e: Exception) {
                        Log.w("WalltakerChanger", "Unable to post wallpaper frame", e)
                    }
                }
            }
        }

        private fun releaseDrawable() {
            (drawable as? Animatable)?.stop()
            drawable?.callback = null
            drawable = null
        }

        private fun clearImage() {
            releaseDrawable()
            val oldTarget = target
            target = null
            oldTarget?.let { Glide.with(applicationContext).clear(it) }
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            surfaceReady = true
            restartFrames()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            if (this.width != width || this.height != height) {
                this.width = width
                this.height = height
                clearImage()
            }
            restartFrames()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            if (visible) (drawable as? Animatable)?.start() else (drawable as? Animatable)?.stop()
            restartFrames()
        }

        private fun restartFrames() {
            handler.removeCallbacks(frame)
            if (visible && surfaceReady && !destroyed) {
                (drawable as? Animatable)?.start()
                handler.post(frame)
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            surfaceReady = false
            handler.removeCallbacks(frame)
            (drawable as? Animatable)?.stop()
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            destroyed = true
            handler.removeCallbacksAndMessages(null)
            clearImage()
            super.onDestroy()
        }

        override fun invalidateDrawable(who: Drawable) { /* The frame loop draws GIF updates. */ }
        override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
            handler.postAtTime(what, who, `when`)
        }
        override fun unscheduleDrawable(who: Drawable, what: Runnable) {
            handler.removeCallbacks(what, who)
        }
    }
}
