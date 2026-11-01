package com.remoit.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
fun TopBar(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(C.Surface2).clickable(onClick = onBack),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = C.Text) }
        Text(title, Modifier.padding(start = 14.dp), style = ts(18, FontWeight.SemiBold))
    }
}

@Composable
fun StepDots(step: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) {
        for (i in 1..3) {
            Box(
                Modifier.height(4.dp).width(if (i == step) 28.dp else 12.dp).clip(RoundedCornerShape(50))
                    .background(if (i <= step) C.Accent else C.Surface2)
            )
        }
    }
}

// ---------------------------------------------------------------- TERMS

@Composable
fun TermsScreen(vm: AppVm) {
    Box(Modifier.fillMaxSize()) {
        PixelField(Modifier.fillMaxWidth().height(240.dp).alpha(0.35f), strength = 0.5f)
        Column(
            Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 16.dp)
        ) {
            Logo(24)
            Gap(30)
            Text("Terms &\nConditions", style = ts(38, FontWeight.Bold, line = 46))
            Gap(12)
            Text(Texts.termsIntro, style = ts(14, color = C.Muted, line = 23))
            Gap(14)
            Pill("SCROLL DOWN TO ACCEPT")
            Gap(22)
            Texts.terms.forEachIndexed { i, (title, body) ->
                val hot = i == 1 || i == 2
                Box(
                    Modifier.fillMaxWidth().padding(bottom = 14.dp).clip(RoundedCornerShape(18.dp))
                        .background(if (hot) C.Accent.copy(alpha = 0.08f) else C.Surface)
                        .border(1.dp, if (hot) C.Accent.copy(alpha = 0.5f) else C.Border, RoundedCornerShape(18.dp))
                        .padding(18.dp)
                ) {
                    Column {
                        Text(title, style = ts(17, FontWeight.SemiBold, if (hot) C.Accent2 else C.Text))
                        Gap(8)
                        LinkText(body, ts(14, color = C.Muted, line = 23))
                    }
                }
            }
            Gap(6)
            Text(Texts.termsOutro, style = ts(14, FontWeight.Medium, C.Text, line = 22))
            Gap(18)
            PxButton("Accept", { Store.termsAccepted = true; vm.screen = Screen.PERMS }, Modifier.fillMaxWidth())
            Gap(30)
        }
    }
}

// ---------------------------------------------------------------- PERMISSIONS

@Composable
fun PermCard(title: String, desc: String, ok: Boolean, optional: Boolean, action: String, onAction: () -> Unit) {
    PxCard(Modifier.fillMaxWidth()) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = ts(17, FontWeight.SemiBold))
                if (optional) Pill("OPTIONAL", C.Muted)
            }
            Gap(6)
            Text(desc, style = ts(13, color = C.Muted, line = 21))
            Gap(12)
            if (ok) Text("✓  Enabled", style = ts(15, FontWeight.SemiBold, C.Good))
            else PxButton(action, onAction, Modifier.fillMaxWidth(), secondary = optional)
        }
    }
}

@Composable
fun PermsScreen(vm: AppVm) {
    val ctx = LocalContext.current
    val tick = vm.resumeTick
    val notifOk = remember(tick) { Perms.notif(ctx) }
    val storageOk = remember(tick) { Perms.storage(ctx) }
    val batteryOk = remember(tick) { Perms.battery(ctx) }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.notifAsked = true; vm.resumeTick++ }
    val storageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.resumeTick++ }

    Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 16.dp)) {
        Logo(22)
        Gap(26)
        Text("Permissions", style = ts(34, FontWeight.Bold))
        Gap(8)
        Text(
            "remoit needs no Gmail and no phone number. Your device itself is your account and receives 5 credits every 24 hours.",
            style = ts(14, color = C.Muted, line = 23)
        )
        Gap(20)

        PermCard(
            "Notifications",
            "Shows progress and tells you when your result is ready, even if you leave the app. Long videos take a while.",
            notifOk, false, if (vm.notifAsked) "Open settings" else "Allow notifications"
        ) {
            if (vm.notifAsked) {
                ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
            } else notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        Gap(14)
        PermCard(
            "Photos and videos",
            "Uses the secure system picker. You choose one file at a time and remoit never sees the rest of your gallery.",
            true, false, ""
        ) {}
        Gap(14)
        PermCard(
            "Save to gallery",
            "Lets the Download button save your result into Movies/remoit or Pictures/remoit.",
            storageOk, false, "Allow saving"
        ) { storageLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) }
        Gap(14)
        PermCard(
            "Device credits identity",
            "A private, one-way device fingerprint tracks your 5 daily credits. It is not your name, number or e-mail.",
            true, false, ""
        ) {}
        Gap(14)
        PermCard(
            "Background processing (optional)",
            "Stops the phone's battery saver from pausing a long video job when the screen turns off.",
            batteryOk, true, "Allow background"
        ) {
            ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName)))
        }
        Gap(24)
        PxButton("Start", { vm.screen = if (Store.setupDone) Screen.BOOT else Screen.SETUP }, Modifier.fillMaxWidth(), enabled = notifOk && storageOk)
        if (!(notifOk && storageOk)) {
            Gap(10)
            Text("Allow the required permissions above to continue.", style = ts(12, color = C.Muted), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }
        Gap(30)
    }
}

// ---------------------------------------------------------------- BOOT

@Composable
fun BootScreen(vm: AppVm) {
    val lines = listOf(
        "Powering on engines", "Loading text finder", "Mapping pixel context",
        "Calibrating inpaint core", "Checking video codecs", "Engines online"
    )
    var idx by remember { mutableIntStateOf(0) }
    val prog by animateFloatAsState((idx + 1f) / lines.size, tween(600), label = "boot")
    LaunchedEffect(Unit) {
        for (i in lines.indices) { idx = i; delay(650) }
        delay(250)
        vm.screen = Screen.HOME
    }
    Box(Modifier.fillMaxSize()) {
        PixelField(Modifier.fillMaxSize().alpha(0.55f), periodMs = 2800, cell = 16.dp, strength = 0.6f)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(C.Bg.copy(alpha = 0.55f), C.Bg.copy(alpha = 0.92f)))))
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(28.dp),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AnimatedMark(120.dp)
            Gap(14)
            Logo(34)
            Gap(8)
            Text("REMOIT ENGINE  v1.0", style = ts(11, FontWeight.Medium, C.Muted, Mono, 3f))
            Gap(44)
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (i in 0..idx) {
                    val cur = i == idx
                    Text(
                        (if (cur && idx < lines.size - 1) "▸ " else "✓ ") + lines[i] + (if (cur && idx < lines.size - 1) " …" else ""),
                        style = ts(if (cur) 17 else 13, if (cur) FontWeight.SemiBold else FontWeight.Normal, if (cur) C.Text else C.Muted, Mono, 0.5f)
                    )
                }
            }
            Gap(34)
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(C.Surface2)) {
                Box(
                    Modifier.fillMaxHeight().fillMaxWidth(prog).clip(RoundedCornerShape(50))
                        .background(Brush.horizontalGradient(listOf(C.Accent, C.Accent2)))
                )
            }
            Gap(10)
            Text((prog * 100).toInt().toString() + "%", style = ts(12, FontWeight.Medium, C.Muted, Mono, 1f))
        }
    }
}

// ---------------------------------------------------------------- HOME

@Composable
private fun UploadBox(title: String, sub: String, hint: String, onClick: () -> Unit) {
    val dash = C.Accent.copy(alpha = 0.7f)
    Text(title, style = ts(24, FontWeight.Bold))
    Gap(6)
    Text(sub, style = ts(13, color = C.Muted, line = 20))
    Gap(16)
    Box(
        Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(24.dp)).background(C.Surface)
            .drawBehind {
                drawRoundRect(
                    color = dash, cornerRadius = CornerRadius(24.dp.toPx()),
                    style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(22f, 16f)))
                )
            }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("+", style = ts(46, FontWeight.Bold, C.Accent))
            Text(hint, style = ts(15, FontWeight.SemiBold))
            Text("from your gallery", style = ts(12, color = C.Muted))
        }
    }
}

@Composable
fun HomeScreen(vm: AppVm) {
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) vm.pickImage(uri) }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) vm.pickVideo(uri) }
    var showSettings by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        PixelField(Modifier.fillMaxWidth().height(280.dp).alpha(0.28f), strength = 0.5f)
        Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Logo(20) }
                Pill(if (Credits.unlimited) "⚡ ∞" else "⚡ ${vm.credits}/${Credits.MAX}", if (vm.credits > 0) C.Good else C.Danger)
                Box(
                    Modifier.padding(start = 10.dp).size(44.dp).clip(RoundedCornerShape(14.dp)).background(C.Surface2)
                        .clickable { showSettings = true },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = C.Text) }
            }
            Gap(34)
            Text("Erase text\nfrom any video.", style = ts(36, FontWeight.Bold, line = 44))
            Gap(10)
            Text("Free daily credits  •  no sign-up  •  on your phone", style = ts(13, color = C.Muted, family = Mono))
            Gap(26)

            PxCard(Modifier.fillMaxWidth()) {
                Column {
                    Text(Texts.aboutTitle, style = ts(20, FontWeight.Bold))
                    Gap(10)
                    Text(Texts.aboutBody, style = ts(14, color = C.Muted, line = 23))
                    Gap(16)
                    Texts.aboutPoints.forEachIndexed { i, (t, d) ->
                        Row(Modifier.padding(vertical = 6.dp)) {
                            Box(
                                Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(C.Accent.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) { Text("${i + 1}", style = ts(13, FontWeight.Bold, C.Accent, Mono)) }
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(t, style = ts(14, FontWeight.SemiBold))
                                Text(d, style = ts(13, color = C.Muted, line = 20))
                            }
                        }
                    }
                    Gap(10)
                    Text(Texts.aboutHonest, style = ts(12, color = C.Warn, line = 19))
                }
            }
            Gap(30)
            Text("↓  Scroll down to start", style = ts(12, FontWeight.Medium, C.Muted, Mono, 1f), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Gap(110)

            UploadBox(
                "Upload image", "One image at a time  •  PNG, JPG or WEBP  •  full resolution kept",
                "Tap to select an image"
            ) { imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

            Gap(40)
            UploadBox(
                "Upload video", "Up to 2 h 30 min  •  same resolution and frame rate  •  original audio kept",
                "Tap to select a video"
            ) { videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }

            if (vm.busy) {
                Gap(16)
                Text("Reading your file…", style = ts(13, color = C.Accent2, family = Mono))
            }
            if (vm.credits <= 0) {
                Gap(14)
                Text("No credits left. A new credit arrives in ${Credits.refillText()}.", style = ts(13, color = C.Danger, line = 20))
            }
            Gap(36)
            LinkText("Made by ${Texts.OWNER_IG}", ts(12, color = C.Muted, family = Mono), Modifier.fillMaxWidth())
            Gap(50)
        }
        if (showSettings) SettingsDialog(vm) { showSettings = false }
        vm.message?.let { m -> MessageDialog(m) { vm.message = null } }
    }
}

// ---------------------------------------------------------------- CONFIRM

@Composable
fun ConfirmScreen(vm: AppVm) {
    val isVideo = vm.kind == MediaKind.VIDEO
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) vm.pickImage(uri) }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) vm.pickVideo(uri) }
    Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 10.dp)) {
        TopBar(if (isVideo) "Your video" else "Your image") { vm.back() }
        StepDots(1)
        Gap(16)
        val bmp = vm.preview
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(C.Surface).border(1.dp, C.Border, RoundedCornerShape(22.dp))) {
            if (bmp != null) {
                Image(bmp, contentDescription = "Selected file", modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp), contentScale = ContentScale.Fit)
            } else {
                Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                    Text("Preview not available", style = ts(13, color = C.Muted))
                }
            }
        }
        Gap(14)
        Text(vm.info, style = ts(13, color = C.Muted, family = Mono))
        if (isVideo) {
            Gap(6)
            Text("Output keeps this resolution and frame rate. The audio is copied untouched.", style = ts(12, color = C.Accent2, line = 19))
        }
        Gap(26)
        PxButton("Continue", { vm.screen = Screen.EDITOR }, Modifier.fillMaxWidth())
        Gap(12)
        PxButton(
            if (isVideo) "Choose a different video" else "Choose a different image",
            {
                if (isVideo) videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                else imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            Modifier.fillMaxWidth(), secondary = true
        )
        Gap(30)
    }
    vm.message?.let { m -> MessageDialog(m) { vm.message = null } }
}
