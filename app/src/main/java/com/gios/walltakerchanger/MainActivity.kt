@file:Suppress("DEPRECATION")

package com.gios.walltakerchanger

import android.annotation.SuppressLint
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.os.PowerManager.WakeLock
import android.provider.Settings
import android.text.Html
import android.text.method.ScrollingMovementMethod
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.bumptech.glide.Glide
import com.google.gson.GsonBuilder
import kotlin.system.exitProcess


@SuppressLint("StaticFieldLeak")
lateinit var imageHome: ImageView

@SuppressLint("StaticFieldLeak")
lateinit var imageLock: ImageView

@SuppressLint("StaticFieldLeak")
lateinit var textHome: TextView

@SuppressLint("StaticFieldLeak")
lateinit var textLock: TextView

@SuppressLint("StaticFieldLeak")
lateinit var panic: Button

@SuppressLint("StaticFieldLeak")
lateinit var start: Button

@SuppressLint("StaticFieldLeak")
lateinit var stop: Button

@SuppressLint("StaticFieldLeak")
lateinit var update: Button

@SuppressLint("StaticFieldLeak")
lateinit var div: View

var linkId: String? = null
var linkIdHome: String? = null
var linkIdLock: String? = null
var setHome: Boolean = false
var setLock: Boolean = false

var post_url: String? = null
var set_by: String? = null

var post_url_home: String? = null
var set_by_home: String? = null

var post_url_lock: String? = null
var set_by_lock: String? = null

lateinit var wl: WakeLock
var linkUpdateReceiver: android.content.BroadcastReceiver? = null
var multiMode = false
var new = false
var livS = false
var livM = false
var iFit = false
var iFitH = false
var iFitL = false
var iFitLive = false
var clos = false
var panicHome = ""
var panicLock = ""
var phone = false
var notifi = false
@Volatile var liveUrl: String? = null
var live_set_by: String? = null
var versio: String = "0"

class MainActivity : AppCompatActivity() {


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        storagePerm()
        ignoreBatteryOptimization()

        version()
        orientation()
        theme()



        div = findViewById(R.id.divider)
        imageHome = findViewById(R.id.imageHome)
        textHome = findViewById(R.id.textHome)
        imageLock = findViewById(R.id.imageLock)
        textLock = findViewById(R.id.textLock)
        textHome.movementMethod = ScrollingMovementMethod()
        textLock.movementMethod = ScrollingMovementMethod()
        stop = findViewById(R.id.stop)
        panic = findViewById(R.id.panic)
        update = findViewById(R.id.update)
        start = findViewById(R.id.start)
        configureLinkUpdateReceiver()
        obtainWallpaper()
        start.setOnClickListener {
            start()
        }
        stop.setOnClickListener {
            stop()
        }
        panic.setOnClickListener {
            panic()
        }
        update.setOnClickListener {
            obtainWallpaper()
            ContextCompat.startForegroundService(this, Intent(this, Service::class.java))
            Updater.forceReconnect(this)
            Toast.makeText(this, "Reconnecting Walltaker...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun version() {
        versio = this.packageManager.getPackageInfo(this.packageName, 0).versionName ?: "0"
    }

    @SuppressLint("SetTextI18n")
    private fun reset() {
        if (multiMode) {
            imageHome.visibility = View.GONE
            imageLock.visibility = View.GONE
            div.visibility = View.GONE
            textHome.text = ""
            imageHome.setImageResource(0)
            textLock.text = ""
            imageLock.setImageResource(0)
            imageHome.visibility = View.VISIBLE
            imageLock.visibility = View.VISIBLE
            div.visibility = View.VISIBLE
            textLock.visibility = View.VISIBLE

        } else {
            imageHome.visibility = View.GONE
            imageLock.visibility = View.GONE
            textLock.visibility = View.GONE
            div.visibility = View.GONE
            textHome.text = ""
            imageHome.setImageResource(0)
            imageHome.visibility = View.VISIBLE
        }
    }

    @SuppressLint("SetTextI18n")
    private fun obtainWallpaper() {

        val sharedPreferences: SharedPreferences =
            PreferenceManager.getDefaultSharedPreferences(this)

        multiMode = sharedPreferences.getBoolean("multimode", false)
        linkId = sharedPreferences.getString("id", "0")
        linkIdHome = sharedPreferences.getString("IdHome", "0")
        linkIdLock = sharedPreferences.getString("IdLock", "0")
        reset()
        textHome.text = if (multiMode) {
            "Waiting for homescreen websocket update..."
        } else {
            "Waiting for websocket update..."
        }
        textLock.text = if (multiMode) {
            "Waiting for lockscreen websocket update..."
        } else {
            ""
        }
    }


    private fun orientation() {
        val orientation = resources.configuration.orientation
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            setContentView(R.layout.landscape_multi)
        } else {
            setContentView(R.layout.portrait_multi)
        }
    }

    private fun storagePerm() {

        if (ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_DENIED
        ) {
            val requestPermissionLauncher = registerForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { _: Boolean ->

            }
            requestPermissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.Q) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:" + this.packageName)
                this.startActivity(intent)
            }

        }
    }


    private fun theme() {
        if (isDarkThemeOn()) {
            this.supportActionBar!!.title =
                Html.fromHtml("<font color='#FFB300'>Walltaker Changer</font>")
        } else {
            this.supportActionBar!!.title =
                Html.fromHtml("<font color='#0D47A1'>Walltaker Changer</font>")
        }
    }

    @SuppressLint("BatteryLife")
    private fun ignoreBatteryOptimization() {
        val intent = Intent()
        val packageName = packageName
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            intent.action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        }
    }

    private fun stop() {
        val sharedPreferences: SharedPreferences =
            PreferenceManager.getDefaultSharedPreferences(this)
        multiMode = sharedPreferences.getBoolean("multimode", false)
        livS = sharedPreferences.getBoolean("liveS", false)
        livM = sharedPreferences.getBoolean("liveM", false)
        Toast.makeText(this, "Stopping Walltaker Changer...", Toast.LENGTH_SHORT).show()

        if (livS || livM) {
            val wallpaperManager = WallpaperManager.getInstance(this@MainActivity)
            wallpaperManager.clear()
            PanicUpdater.pUpdate(this@MainActivity)
            stopService(Intent(this, Wallpapz::class.java))
            clos = true
        }
        runCatching { linkUpdateReceiver?.let { unregisterReceiver(it) } }
        stopService(Intent(this, Service::class.java))
        finishAndRemoveTask()
        exitProcess(0)

    }

    private fun panic() {
        runOnUiThread {
            val sharedPreferences: SharedPreferences =
                PreferenceManager.getDefaultSharedPreferences(this@MainActivity)
            livS = sharedPreferences.getBoolean("liveS", false)
            livM = sharedPreferences.getBoolean("liveM", false)

            val wallpaperManager = WallpaperManager.getInstance(this@MainActivity)
            wallpaperManager.clear()
            PanicUpdater.pUpdate(this@MainActivity)

            stopService(Intent(this@MainActivity, Service::class.java))
            if (livS || livM) {
                stopService(Intent(this@MainActivity, Wallpapz::class.java))
                clos = true
            }
            runCatching { linkUpdateReceiver?.let { unregisterReceiver(it) } }

            finishAndRemoveTask()
            exitProcess(0)
        }
    }


    private fun start() {
        val sharedPreferences: SharedPreferences =
            PreferenceManager.getDefaultSharedPreferences(this)
        multiMode = sharedPreferences.getBoolean("multimode", false)
        livS = sharedPreferences.getBoolean("liveS", false)
        livM = sharedPreferences.getBoolean("liveM", false)
        linkId = sharedPreferences.getString("id", "0")
        linkIdHome = sharedPreferences.getString("IdHome", "0")
        linkIdLock = sharedPreferences.getString("IdLock", "0")
        setHome = sharedPreferences.getBoolean("wallpaper", false)
        setLock = sharedPreferences.getBoolean("lockscreen", false)
        if (!multiMode && (linkId == "0" || linkId.isNullOrEmpty()) ) {
            Toast.makeText(this, "Set an id", Toast.LENGTH_SHORT).show()
        } else if (!multiMode && !livS && !setHome && !setLock) {
            Toast.makeText(this, "Enable Homescreen or lockscreen", Toast.LENGTH_SHORT).show()
        } else if (multiMode && (linkIdHome == "0" || linkIdLock == "0" || linkIdHome.isNullOrEmpty() || linkIdLock.isNullOrEmpty())) {
            Toast.makeText(this, "Set an id for multi mode", Toast.LENGTH_SHORT).show()
        } else if (if (multiMode) livM else livS) {
            Toast.makeText(this, "Starting Walltaker Changer...", Toast.LENGTH_SHORT).show()

            ContextCompat.startForegroundService(this, Intent(this, Service::class.java)
                .putExtra("reconnect", true))
            val component = ComponentName(packageName, "$packageName.Wallpapz")
            if (WallpaperManager.getInstance(this).wallpaperInfo?.component == component) {
                moveTaskToBack(true)
                return
            }
            val intent2 = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
            intent2.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component)
            startActivity(intent2)

            // Keep Android's live wallpaper confirmation visible until the user finishes it.
        } else {
            Toast.makeText(this, "Starting Walltaker Changer...", Toast.LENGTH_SHORT).show()

            ContextCompat.startForegroundService(this, Intent(this, Service::class.java)
                .putExtra("reconnect", true))
            moveTaskToBack(true)
        }
    }

    private fun isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == UI_MODE_NIGHT_YES
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                settings()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun settings() {
        val intent = Intent(applicationContext, SettingsActivity::class.java)
        startActivity(intent)
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun configureLinkUpdateReceiver() {
        val filter = IntentFilter()
        filter.addAction(ACTION_WALLTAKER_LINK_UPDATE)
        linkUpdateReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val linkJson = intent?.getStringExtra(EXTRA_WALLTAKER_LINK) ?: return
                val target = intent.getStringExtra(EXTRA_WALLTAKER_TARGET) ?: return
                val data = runCatching {
                    GsonBuilder().create().fromJson(linkJson, LinkData::class.java)
                }.getOrNull() ?: return

                runOnUiThread {
                    displayLinkUpdate(data, target)
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(linkUpdateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(linkUpdateReceiver, filter)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun displayLinkUpdate(data: LinkData, target: String) {
        val targetText = when (target) {
            "HOME" -> textHome
            "LOCK" -> textLock
            "LIVE" -> textHome
            else -> textHome
        }
        val targetImage = when (target) {
            "LOCK" -> imageLock
            else -> imageHome
        }
        val heading = when (target) {
            "HOME" -> "HomeScreen Link\n"
            "LOCK" -> "Lockscreen Link\n"
            else -> ""
        }
        val setter = if (data.set_by.isNullOrEmpty()) {
            "The wallpaper has been set by an anonymous user"
        } else {
            "The wallpaper has been set by ${data.set_by}"
        }
        val description = if (data.post_description.isNullOrEmpty()) {
            ""
        } else {
            "\n\nThe post description is ${data.post_description}"
        }

        targetText.text =
            "${heading}You are using ${data.username}'s id ${data.id}\n\n$setter$description\n\nThe link terms are: ${data.terms}\n\nThe blacklist tags are: ${data.blacklist}"

        if (!data.post_url.isNullOrEmpty()) {
            targetImage.scaleType = ImageView.ScaleType.CENTER_INSIDE
            Glide.with(this).load(data.post_url).fitCenter().into(targetImage)
        }
    }
}

@Suppress("PropertyName")
class LinkData(
    var id: String,
    var username: String,
    var terms: String,
    var blacklist: String,
    var post_url: String,
    var post_thumbnail_url: String,
    var post_description: String,
    var set_by: String,
)
