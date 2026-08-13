package com.gios.walltakerchanger

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.WallpaperManager
import android.content.Context
import android.content.Context.NOTIFICATION_SERVICE
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import com.bumptech.glide.Glide
import com.bumptech.glide.request.FutureTarget
import com.github.kittinunf.fuel.Fuel
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.File
import java.util.concurrent.TimeUnit

const val ACTION_WALLTAKER_LINK_UPDATE = "com.gios.walltakerchanger.LINK_UPDATE"
const val EXTRA_WALLTAKER_LINK = "com.gios.walltakerchanger.extra.LINK"
const val EXTRA_WALLTAKER_TARGET = "com.gios.walltakerchanger.extra.TARGET"
private const val TAG = "WalltakerChanger"

@Suppress("DEPRECATION")
class Updater {
    companion object {
        private val gson = GsonBuilder().create()
        private val handler = Handler(Looper.getMainLooper())
        private val lock = Any()
        private val client = OkHttpClient.Builder()
            .pingInterval(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        private var lastUrl = ""
        private var lastUrlHome = ""
        private var lastUrlLock = ""
        private var lastSingleData: LinkData? = null
        private var lastHomeData: LinkData? = null
        private var lastLockData: LinkData? = null
        private var lastLiveData: LinkData? = null
        private var activeKey = ""
        private var reconnectPending = false
        private val sockets = mutableListOf<WebSocket>()

        fun update(context: Context) {
            val appContext = context.applicationContext
            val sharedPreferences = refreshSettings(appContext)

            val cableUrl = walltakerCableUrl(sharedPreferences)
            val subscriptions = buildSubscriptions()
            val newKey = "$cableUrl|${subscriptions.joinToString { "${it.linkId}:${it.target}" }}"

            synchronized(lock) {
                if (newKey == activeKey && sockets.isNotEmpty()) {
                    return
                }
                closeSockets()
                activeKey = newKey
                reconnectPending = false
                subscriptions.forEach { subscription ->
                    sockets.add(openSocket(appContext, cableUrl, subscription))
                }
            }
        }

        fun stop() {
            synchronized(lock) {
                activeKey = ""
                reconnectPending = false
                closeSockets()
            }
        }

        fun forceReconnect(context: Context) {
            synchronized(lock) {
                activeKey = ""
                reconnectPending = false
                closeSockets()
            }
            update(context)
        }

        fun onSettingsChanged(context: Context, key: String?) {
            if (key == null) {
                return
            }
            synchronized(lock) {
                if (activeKey.isEmpty() && sockets.isEmpty()) {
                    return
                }
            }
            when (key) {
                "serverUrl", "id", "IdHome", "IdLock", "multimode", "liveS", "liveM" -> {
                    forceReconnect(context)
                }

                "wallpaper", "lockscreen", "phone", "iFit", "iFitH", "iFitL", "iFitLive" -> {
                    refreshSettings(context.applicationContext)
                    reapplyCurrentWallpaper(context.applicationContext)
                }
            }
        }

        private fun refreshSettings(context: Context): SharedPreferences {
            val sharedPreferences: SharedPreferences =
                PreferenceManager.getDefaultSharedPreferences(context)

            multiMode = sharedPreferences.getBoolean("multimode", false)
            livS = sharedPreferences.getBoolean("liveS", false)
            livM = sharedPreferences.getBoolean("liveM", false)
            iFit = sharedPreferences.getBoolean("iFit", false)
            iFitH = sharedPreferences.getBoolean("iFitH", false)
            iFitL = sharedPreferences.getBoolean("iFitL", false)
            iFitLive = sharedPreferences.getBoolean("iFitLive", false)
            notifi = sharedPreferences.getBoolean("notifi", false)
            phone = sharedPreferences.getBoolean("phone", false)
            setHome = sharedPreferences.getBoolean("wallpaper", false)
            setLock = sharedPreferences.getBoolean("lockscreen", false)

            linkId = sharedPreferences.getString("id", "0")
            linkIdHome = sharedPreferences.getString("IdHome", "0")
            linkIdLock = sharedPreferences.getString("IdLock", "0")

            return sharedPreferences
        }

        private fun reapplyCurrentWallpaper(context: Context) {
            if (livS) {
                if (!liveUrl.isNullOrEmpty()) {
                    new = true
                }
                return
            }

            if (multiMode) {
                if (livM) {
                    if (!liveUrl.isNullOrEmpty()) {
                        new = true
                    }
                } else {
                    lastHomeData?.let {
                        handleStaticUpdate(
                            context,
                            it,
                            listOf(WallpaperManager.FLAG_SYSTEM),
                            iFitH,
                            "",
                            "download1",
                            skipCache = true
                        ) { lastUrlHome = it }
                    }
                }
                lastLockData?.let {
                    handleStaticUpdate(
                        context,
                        it,
                        listOf(WallpaperManager.FLAG_LOCK),
                        iFitL,
                        "",
                        "download2",
                        skipCache = true
                    ) { lastUrlLock = it }
                }
            } else {
                val flags = mutableListOf<Int>()
                if (setHome) flags.add(WallpaperManager.FLAG_SYSTEM)
                if (setLock) flags.add(WallpaperManager.FLAG_LOCK)
                if (flags.isNotEmpty()) {
                    lastSingleData?.let {
                        handleStaticUpdate(
                            context,
                            it,
                            flags,
                            iFit,
                            "",
                            "download",
                            skipCache = true
                        ) { lastUrl = it }
                    }
                }
            }
        }

        private fun buildSubscriptions(): List<LinkSubscription> {
            val subscriptions = mutableListOf<LinkSubscription>()
            if (livS) {
                subscriptions.add(LinkSubscription(linkId, LinkTarget.LIVE))
            } else if (multiMode) {
                subscriptions.add(LinkSubscription(linkIdHome, if (livM) LinkTarget.LIVE else LinkTarget.HOME))
                subscriptions.add(LinkSubscription(linkIdLock, LinkTarget.LOCK))
            } else {
                subscriptions.add(LinkSubscription(linkId, LinkTarget.SINGLE))
            }
            return subscriptions.filter { !it.linkId.isNullOrBlank() && it.linkId != "0" }
        }

        private fun openSocket(
            context: Context,
            cableUrl: String,
            subscription: LinkSubscription
        ): WebSocket {
            val request = Request.Builder()
                .url(cableUrl)
                .header("User-Agent", "Walltaker-Changer/$versio")
                .build()

            return client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val identifier = JsonObject().apply {
                        addProperty("channel", "LinkChannel")
                        val numericLinkId = subscription.linkId?.toIntOrNull()
                        if (numericLinkId != null) {
                            addProperty("link_id", numericLinkId)
                        } else {
                            addProperty("link_id", subscription.linkId)
                        }
                        addProperty("client", "Walltaker-Changer/$versio")
                    }
                    val message = JsonObject().apply {
                        addProperty("command", "subscribe")
                        addProperty("identifier", gson.toJson(identifier))
                    }
                    webSocket.send(gson.toJson(message))
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val envelope = runCatching {
                        gson.fromJson(text, JsonObject::class.java)
                    }.getOrNull() ?: return

                    val messageElement = envelope.get("message") ?: return
                    if (!messageElement.isJsonObject) {
                        return
                    }
                    val message = messageElement.asJsonObject
                    val success = message.get("success")?.asBoolean ?: true
                    if (!success) {
                        println(message.get("why")?.asString ?: "Walltaker websocket update failed")
                        return
                    }

                    val data = runCatching {
                        gson.fromJson(message, LinkData::class.java)
                    }.getOrNull() ?: return
                    handleLinkUpdate(context, data, subscription.target)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.w(TAG, "Walltaker websocket failed: ${t.message}", t)
                    scheduleReconnect(context)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.i(TAG, "Walltaker websocket closed: code=$code reason=$reason")
                    if (code == 1000 && reason == "settings changed") {
                        return
                    }
                    scheduleReconnect(context)
                }
            })
        }

        private fun scheduleReconnect(context: Context) {
            synchronized(lock) {
                if (reconnectPending || activeKey.isEmpty()) {
                    return
                }
                reconnectPending = true
            }
            handler.postDelayed({
                synchronized(lock) {
                    reconnectPending = false
                    activeKey = ""
                }
                update(context)
            }, 10000)
        }

        private fun closeSockets() {
            sockets.forEach { it.close(1000, "settings changed") }
            sockets.clear()
        }

        private fun handleLinkUpdate(context: Context, data: LinkData, target: LinkTarget) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WalltakerChanger:WAKEUP")
            wakeLock.acquire(10 * 60 * 1000L)
            try {
                broadcastLinkUpdate(context, data, target)
                when (target) {
                    LinkTarget.LIVE -> {
                        lastLiveData = data
                        handleLiveUpdate(context, data)
                    }

                    LinkTarget.HOME -> {
                        lastHomeData = data
                        handleStaticUpdate(
                            context,
                            data,
                            listOf(WallpaperManager.FLAG_SYSTEM),
                            iFitH,
                            lastUrlHome,
                            "download1"
                        ) { lastUrlHome = it }
                    }

                    LinkTarget.LOCK -> {
                        lastLockData = data
                        handleStaticUpdate(
                            context,
                            data,
                            listOf(WallpaperManager.FLAG_LOCK),
                            iFitL,
                            lastUrlLock,
                            "download2"
                        ) { lastUrlLock = it }
                    }

                    LinkTarget.SINGLE -> {
                        lastSingleData = data
                        val flags = mutableListOf<Int>()
                        if (setHome) flags.add(WallpaperManager.FLAG_SYSTEM)
                        if (setLock) flags.add(WallpaperManager.FLAG_LOCK)
                        if (flags.isNotEmpty()) {
                            handleStaticUpdate(
                                context,
                                data,
                                flags,
                                iFit,
                                lastUrl,
                                "download"
                            ) { lastUrl = it }
                        }
                    }
                }
            } finally {
                if (wakeLock.isHeld) {
                    wakeLock.release()
                }
            }
        }

        private fun broadcastLinkUpdate(context: Context, data: LinkData, target: LinkTarget) {
            val intent = android.content.Intent(ACTION_WALLTAKER_LINK_UPDATE)
                .setPackage(context.packageName)
                .putExtra(EXTRA_WALLTAKER_LINK, gson.toJson(data))
                .putExtra(EXTRA_WALLTAKER_TARGET, target.name)
            context.sendBroadcast(intent)
        }

        private fun handleLiveUpdate(context: Context, data: LinkData) {
            post_url = data.post_url
            live_set_by = data.set_by
            if (!post_url.isNullOrEmpty() && liveUrl != post_url) {
                if (notifi) {
                    notifier(context)
                }
                val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
                val downloadKey = if (multiMode) "download1" else "download"
                if (sharedPreferences.getBoolean(downloadKey, false)) {
                    downloadFile(post_url)
                }
                new = true
                liveUrl = data.post_url
            }
        }

        private fun handleStaticUpdate(
            context: Context,
            data: LinkData,
            wallpaperFlags: List<Int>,
            fitImage: Boolean,
            lastKnownUrl: String,
            downloadKey: String,
            skipCache: Boolean = false,
            updateLastUrl: (String) -> Unit
        ) {
            val imageUrl = data.post_url
            if (imageUrl.isNullOrEmpty()) {
                return
            }
            if (!skipCache && imageUrl == lastKnownUrl) {
                return
            }
            if (wallpaperFlags.contains(WallpaperManager.FLAG_SYSTEM)) {
                post_url_home = imageUrl
                set_by_home = data.set_by
            }
            if (wallpaperFlags.contains(WallpaperManager.FLAG_LOCK)) {
                post_url_lock = imageUrl
                set_by_lock = data.set_by
            }
            post_url = imageUrl
            set_by = data.set_by

            if (notifi) {
                notifier(context)
            }

            lateinit var bitmap: Bitmap
            val wallpaperManager = WallpaperManager.getInstance(context)
            runBlocking {
                val job = launch {
                    val displayMetrics = DisplayMetrics()
                    val windowsManager =
                        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                    windowsManager.defaultDisplay.getMetrics(displayMetrics)
                    val width = displayMetrics.widthPixels
                    val height = displayMetrics.heightPixels

                    val futureTarget: FutureTarget<Bitmap> =
                        loadBitmapTarget(context, imageUrl, width, height)
                    bitmap = withContext(Dispatchers.IO) {
                        try {
                            futureTarget.get()
                        } catch (e: Exception) {
                            Glide.with(context).clear(futureTarget)
                            if (data.post_thumbnail_url.isNullOrEmpty()) {
                                throw e
                            }
                            Log.w(TAG, "Failed to load post_url, trying thumbnail fallback: $imageUrl", e)
                            val fallbackTarget =
                                loadBitmapTarget(context, data.post_thumbnail_url, width, height)
                            try {
                                fallbackTarget.get()
                            } finally {
                                Glide.with(context).clear(fallbackTarget)
                            }
                        }
                    }
                    val oriz: Boolean = bitmap.width >= bitmap.height
                    if (phone && oriz) {
                        bitmap = bitmapRotator(bitmap)
                    }
                    bitmap =
                        if (fitImage) {
                            bitmapResizer(bitmap, height, width)
                        } else {
                            returnBitmap(bitmap, width, height)
                        }
                    Glide.with(context).clear(futureTarget)
                }
                job.join()
                try {
                    println("Setting wallpaper")
                    wallpaperFlags.forEach { wallpaperFlag ->
                        wallpaperManager.setBitmap(bitmap, null, true, wallpaperFlag)
                    }
                    updateLastUrl(imageUrl)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to set wallpaper for $imageUrl", e)
                    updateLastUrl("")
                }
            }

            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
            if (sharedPreferences.getBoolean(downloadKey, false)) {
                downloadFile(imageUrl)
            }
        }

        private fun notifier(context: Context) {
            val notificationManager =
                context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager

            val id = "walltaker_changer"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    id,
                    "Walltaker Changer notification",
                    NotificationManager.IMPORTANCE_DEFAULT
                )
                notificationManager.createNotificationChannel(channel)
            }

            val builder: NotificationCompat.Builder =
                NotificationCompat.Builder(context, id)
                    .setSmallIcon(R.mipmap.ic_launcher_foreground)
                    .setContentTitle("Setting Wallpaper")
                    .setContentText(
                        ((if (multiMode) {
                            if (livM) {
                                if (live_set_by.isNullOrEmpty() && !set_by_lock.isNullOrEmpty()) {
                                    "Live wallpaper set by an anonymous user | Lockscreen set by $set_by_lock"
                                } else if (!live_set_by.isNullOrEmpty() && set_by_lock.isNullOrEmpty()) {
                                    "Live wallpaper set by $live_set_by | Lockscreen set by an anonymous user"
                                } else if (live_set_by.isNullOrEmpty() && set_by_lock.isNullOrEmpty()) {
                                    "Live wallpaper set by an anonymous user | Lockscreen set by an anonymous user"
                                } else {
                                    "Live wallpaper set by $live_set_by | Lockscreen set by $set_by_lock"
                                }
                            } else {
                                if (set_by_home.isNullOrEmpty() && !set_by_lock.isNullOrEmpty()) {
                                    "Live wallpaper set by an anonymous user | Lockscreen set by $set_by_lock"
                                } else if (!set_by_home.isNullOrEmpty() && set_by_lock.isNullOrEmpty()) {
                                    "Live wallpaper set by $live_set_by | Lockscreen set by an anonymous user"
                                } else if (set_by_home.isNullOrEmpty() && set_by_lock.isNullOrEmpty()) {
                                    "Live wallpaper set by an anonymous user | Lockscreen set by an anonymous user"
                                } else {
                                    "Live wallpaper set by $set_by_home | Lockscreen set by $set_by_lock"
                                }
                            }
                        } else {
                            if (livS) {
                                if (live_set_by.isNullOrEmpty()) {
                                    "Wallpaper set by an anonymous user"
                                } else {
                                    "Wallpaper set by $live_set_by"
                                }
                            } else {
                                if (set_by.isNullOrEmpty()) {
                                    "Wallpaper set by an anonymous user"
                                } else {
                                    "Wallpaper set by $set_by"
                                }
                            }
                        }).toString())
                    )
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            notificationManager.notify(0, builder.build())
        }

        private fun bitmapResizer(bitmap: Bitmap, targetHeight: Int, targetWidth: Int): Bitmap {

            val resizedBitmap =
                Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(resizedBitmap)
            val left = (targetWidth - bitmap.width) / 2f
            val top = (targetHeight - bitmap.height) / 2f
            canvas.drawBitmap(bitmap, left, top, null)
            return resizedBitmap

        }

        private fun loadBitmapTarget(
            context: Context,
            url: String,
            width: Int,
            height: Int
        ): FutureTarget<Bitmap> {
            return Glide.with(context)
                .asBitmap()
                .load(url)
                .fitCenter()
                .submit(width, height)
        }

        private fun bitmapRotator(bitmap: Bitmap?): Bitmap {
            val bOutput: Bitmap
            val degrees = 90f
            val matrix = Matrix()
            matrix.setRotate(degrees)
            bOutput = Bitmap.createBitmap(bitmap!!, 0, 0, bitmap.width, bitmap.height, matrix, true)
            return bOutput
        }

        private fun returnBitmap(targetBmp: Bitmap, height: Int, width: Int): Bitmap {
            val matrix = Matrix()
            matrix.setRectToRect(
                RectF(0f, 0f, targetBmp.width.toFloat(), targetBmp.height.toFloat()),
                RectF(0f, 0f, width.toFloat(), height.toFloat()),
                Matrix.ScaleToFit.CENTER
            )
            return Bitmap.createBitmap(
                targetBmp,
                0,
                0,
                targetBmp.width,
                targetBmp.height,
                matrix,
                true
            )
        }

        private fun downloadFile(url: String?) {
            if (url != null) {
                val uri = Uri.parse(url)
                val dI = uri.lastPathSegment.toString()
                if (!File(Environment.getExternalStorageDirectory().absolutePath + "/WTchanger/$dI").exists()) {
                    val folder =
                        File(Environment.getExternalStorageDirectory().absolutePath + "/WTchanger")
                    val destinationFile = File(folder, dI)

                    if (!folder.exists()) {
                        folder.mkdirs()
                    }
                    try {
                        Fuel.download(url).destination { _, _ -> destinationFile }
                            .response { _, _, result ->
                                when (result) {
                                    is com.github.kittinunf.result.Result.Success -> {
                                        println("downloaded")
                                    }

                                    is com.github.kittinunf.result.Result.Failure -> {
                                        println("Error")
                                    }
                                }
                            }
                    } catch (e: Exception) {
                        println(e)
                    }
                }
            }
        }
    }
}

private data class LinkSubscription(
    val linkId: String?,
    val target: LinkTarget
)

private enum class LinkTarget {
    SINGLE,
    HOME,
    LOCK,
    LIVE
}
