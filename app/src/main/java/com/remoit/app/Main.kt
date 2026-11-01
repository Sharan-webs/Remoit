package com.remoit.app

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

enum class Screen { TERMS, PERMS, SETUP, BOOT, HOME, CONFIRM, EDITOR, PROCESS, RESULT, HISTORY }

enum class MediaKind { IMAGE, VIDEO }

object Perms {
    fun notif(c: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun storage(c: Context): Boolean =
        Build.VERSION.SDK_INT >= 29 ||
            ContextCompat.checkSelfPermission(c, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    fun battery(c: Context): Boolean =
        c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName)

    fun requiredOk(c: Context) = notif(c) && storage(c)
}

class AppVm(app: Application) : AndroidViewModel(app) {
    var screen by mutableStateOf(
        if (!Store.termsAccepted) Screen.TERMS else if (!Perms.requiredOk(app)) Screen.PERMS else if (!Store.setupDone) Screen.SETUP else Screen.BOOT
    )
    var resumeTick by mutableIntStateOf(0)
    var notifAsked by mutableStateOf(false)

    var kind by mutableStateOf<MediaKind?>(null)
    var videoUri by mutableStateOf<Uri?>(null)
    var imagePath by mutableStateOf<String?>(null)
    var asJpeg by mutableStateOf(false)
    var preview by mutableStateOf<ImageBitmap?>(null)
    var previewBmp: Bitmap? = null
    var info by mutableStateOf("")
    var dispW by mutableIntStateOf(16)
    var dispH by mutableIntStateOf(9)
    var durMs by mutableLongStateOf(0L)

    val ed = EditorState()

    var result by mutableStateOf<Project?>(null)
    var credits by mutableIntStateOf(Credits.remaining())
    var message by mutableStateOf<String?>(null)
    var showNoCredits by mutableStateOf(false)
    var busy by mutableStateOf(false)

    init {
        // the app was closed while a job was running: pick it up again
        if (Store.termsAccepted) {
            when (val s = Hub.state.value) {
                is Hub.State.Running, is Hub.State.Failed -> screen = Screen.PROCESS
                is Hub.State.Done -> { result = s.project; screen = Screen.RESULT }
                else -> {}
            }
        }
    }

    fun refreshCredits() { credits = Credits.remaining() }

    private fun clearMedia() {
        imagePath?.let { File(it).delete() }
        imagePath = null
        videoUri = null
        kind = null
        preview = null
        previewBmp = null
        info = ""
        durMs = 0L
        ed.reset()
    }

    fun pickImage(uri: Uri) {
        val ctx = getApplication<Application>()
        busy = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val f = File(ctx.cacheDir, "input_" + System.currentTimeMillis() + ".img")
                val ins = ctx.contentResolver.openInputStream(uri) ?: throw IllegalArgumentException("Could not open the image.")
                ins.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                val mime = (ctx.contentResolver.getType(uri) ?: "").lowercase()
                val bounds = BitmapFactory.Options()
                bounds.inJustDecodeBounds = true
                BitmapFactory.decodeFile(f.absolutePath, bounds)
                val bm = ImageIO.loadPreview(f.absolutePath, 1600)
                var iw = bounds.outWidth
                var ih = bounds.outHeight
                if ((bm.width > bm.height) != (iw > ih)) { val t = iw; iw = ih; ih = t }
                val mb = f.length() / 1048576.0
                val text = "$iw × $ih px  •  " + String.format("%.2f", mb) + " MB"
                withContext(Dispatchers.Main) {
                    clearMedia()
                    imagePath = f.absolutePath
                    asJpeg = mime.contains("jpeg") || mime.contains("jpg")
                    kind = MediaKind.IMAGE
                    previewBmp = bm
                    preview = bm.asImageBitmap()
                    dispW = bm.width
                    dispH = bm.height
                    info = text
                    busy = false
                    screen = Screen.CONFIRM
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) { busy = false; message = e.message ?: "Could not read this image." }
            }
        }
    }

    fun pickVideo(uri: Uri) {
        val ctx = getApplication<Application>()
        busy = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val p = VideoPipeline.probe(ctx, uri)
                if (p.durationMs <= 0L) throw IllegalArgumentException("This video could not be read.")
                if (p.durationMs > VideoPipeline.MAX_MS + 2000) {
                    throw IllegalArgumentException("This video is " + fmtTime(p.durationMs) + " long. The maximum is 2 hours 30 minutes.")
                }
                val r = MediaMetadataRetriever()
                var frame: Bitmap? = null
                try {
                    r.setDataSource(ctx, uri)
                    frame = r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } finally {
                    try { r.release() } catch (e: Exception) { }
                }
                if (frame != null && max(frame.width, frame.height) > 1280) {
                    val s = 1280f / max(frame.width, frame.height)
                    frame = Bitmap.createScaledBitmap(frame, max(1, (frame.width * s).toInt()), max(1, (frame.height * s).toInt()), true)
                }
                val mbps = if (p.bitrate > 0) String.format("%.1f", p.bitrate / 1_000_000.0) + " Mbps" else "bitrate unknown"
                val text = "${p.dispW} × ${p.dispH} px  •  " + fmtTime(p.durationMs) + "  •  " + mbps
                val fr = frame
                withContext(Dispatchers.Main) {
                    clearMedia()
                    videoUri = uri
                    kind = MediaKind.VIDEO
                    previewBmp = fr
                    preview = fr?.asImageBitmap()
                    dispW = p.dispW
                    dispH = p.dispH
                    durMs = p.durationMs
                    info = text
                    busy = false
                    screen = Screen.CONFIRM
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) { busy = false; message = e.message ?: "Could not read this video." }
            }
        }
    }

    fun startJob(ctx: Context) {
        val k = kind ?: return
        if (ed.isEmpty()) {
            message = "Nothing is marked yet. Tap on the text to create a box, or use the pen."
            return
        }
        if (Credits.remaining() <= 0) { showNoCredits = true; return }
        val spec = ed.toSpec()
        Hub.pending = if (k == MediaKind.VIDEO) JobSpec(true, videoUri, null, false, spec)
        else JobSpec(false, null, imagePath, asJpeg, spec)
        Hub.state.value = Hub.State.Running("Starting", 0f, 0, "")
        ContextCompat.startForegroundService(ctx, Intent(ctx, RenderService::class.java))
        screen = Screen.PROCESS
    }

    fun cancelJob(ctx: Context) {
        ctx.stopService(Intent(ctx, RenderService::class.java))
        Hub.state.value = Hub.State.Idle
        screen = Screen.EDITOR
    }

    fun newProject() {
        Hub.state.value = Hub.State.Idle
        clearMedia()
        result = null
        refreshCredits()
        screen = Screen.HOME
    }

    fun refreshApp(ctx: Context) {
        ctx.stopService(Intent(ctx, RenderService::class.java))
        Store.wipe(ctx)
        Hub.state.value = Hub.State.Idle
        clearMedia()
        result = null
        refreshCredits()
        screen = Screen.TERMS
    }

    fun back() {
        screen = when (screen) {
            Screen.CONFIRM -> Screen.HOME
            Screen.EDITOR -> Screen.CONFIRM
            Screen.HISTORY -> Screen.HOME
            Screen.RESULT -> Screen.HOME
            else -> screen
        }
    }
}

fun fmtTime(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%02d:%02d", m, sec)
}

class MainActivity : ComponentActivity() {
    private val vm: AppVm by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent { PxTheme { RemoitRoot(vm) } }
    }

    override fun onResume() {
        super.onResume()
        vm.resumeTick++
        vm.refreshCredits()
    }
}

@Composable
fun RemoitRoot(vm: AppVm) {
    BackHandler(enabled = vm.screen in listOf(Screen.CONFIRM, Screen.EDITOR, Screen.HISTORY, Screen.RESULT)) { vm.back() }
    Box(Modifier.fillMaxSize().background(C.Bg)) {
        Crossfade(targetState = vm.screen, animationSpec = tween(320), label = "screens") { s ->
            when (s) {
                Screen.TERMS -> TermsScreen(vm)
                Screen.PERMS -> PermsScreen(vm)
                Screen.SETUP -> SetupScreen(vm)
                Screen.BOOT -> BootScreen(vm)
                Screen.HOME -> HomeScreen(vm)
                Screen.CONFIRM -> ConfirmScreen(vm)
                Screen.EDITOR -> EditorScreen(vm)
                Screen.PROCESS -> ProcessScreen(vm)
                Screen.RESULT -> ResultScreen(vm)
                Screen.HISTORY -> HistoryScreen(vm)
            }
        }
    }
}
