package com.remoit.app

import android.graphics.BitmapFactory
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun fmtEta(sec: Int): String = if (sec < 60) "$sec s" else if (sec < 3600) "${sec / 60} min ${sec % 60} s" else "${sec / 3600} h ${(sec % 3600) / 60} min"

// ---------------------------------------------------------------- PROCESS

@Composable
fun ProcessScreen(vm: AppVm) {
    val ctx = LocalContext.current
    val st by Hub.state.collectAsState()
    val tips = listOf(
        "remoit only changes the pixels you marked.",
        "The original audio is copied without any change.",
        "Processing continues if you leave the app.",
        "Keep the phone charged for long videos."
    )
    var tip by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(3800); tip = (tip + 1) % tips.size } }
    LaunchedEffect(st) {
        val s = st
        if (s is Hub.State.Done) { vm.result = s.project; vm.refreshCredits(); vm.screen = Screen.RESULT }
    }

    Box(Modifier.fillMaxSize()) {
        PixelField(Modifier.fillMaxSize().alpha(0.32f), periodMs = 2400, cell = 18.dp, strength = 0.6f)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(C.Bg.copy(alpha = 0.4f), C.Bg.copy(alpha = 0.94f)))))
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(26.dp),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val s = st
            if (s is Hub.State.Failed) {
                Text("Something went wrong", style = ts(24, FontWeight.Bold, C.Danger), textAlign = TextAlign.Center)
                Gap(12)
                Text(s.message, style = ts(14, color = C.Muted, line = 22), textAlign = TextAlign.Center)
                Gap(28)
                PxButton("Back", { Hub.state.value = Hub.State.Idle; vm.screen = Screen.EDITOR }, Modifier.fillMaxWidth())
            } else {
                val run = s as? Hub.State.Running
                val p by animateFloatAsState(run?.progress ?: 0f, tween(500), label = "p")
                Text("Removing the text", style = ts(26, FontWeight.Bold))
                Gap(26)
                Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize()) {
                        val w = 12.dp.toPx()
                        val pad = w / 2f
                        val sz = Size(size.width - w, size.height - w)
                        drawArc(color = C.Surface2, startAngle = -90f, sweepAngle = 360f, useCenter = false, topLeft = Offset(pad, pad), size = sz, style = Stroke(w))
                        drawArc(
                            brush = Brush.sweepGradient(listOf(C.Accent, C.Accent2, C.Accent)),
                            startAngle = -90f, sweepAngle = 360f * p, useCenter = false,
                            topLeft = Offset(pad, pad), size = sz, style = Stroke(w, cap = StrokeCap.Round)
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text((p * 100).toInt().toString() + "%", style = ts(46, FontWeight.Bold))
                        Text("approx. " + fmtEta(run?.etaSec ?: 0) + " left", style = ts(12, color = C.Muted, family = Mono))
                    }
                }
                Gap(26)
                Text(run?.stage ?: "Working", style = ts(16, FontWeight.SemiBold, C.Accent2), textAlign = TextAlign.Center)
                val d = run?.detail.orEmpty()
                if (d.isNotEmpty()) {
                    Gap(10)
                    Text(d, style = ts(12, color = C.Muted, line = 19), textAlign = TextAlign.Center, maxLines = 5)
                }
                Gap(34)
                Text(tips[tip], style = ts(12, color = C.Muted, family = Mono), textAlign = TextAlign.Center)
                Gap(30)
                PxButton("Cancel", { vm.cancelJob(ctx) }, Modifier.fillMaxWidth(), secondary = true)
            }
        }
    }
}

// ---------------------------------------------------------------- RESULT

@Composable
fun ResultScreen(vm: AppVm) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val p = vm.result
    Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 12.dp)) {
        Logo(20)
        Gap(18)
        if (p == null) {
            Text("No result found.", style = ts(16, color = C.Muted))
            Gap(16)
            PxButton("New project", { vm.newProject() }, Modifier.fillMaxWidth())
        } else {
            Text(if (p.isVideo) "Your video is ready" else "Your image is ready", style = ts(28, FontWeight.Bold))
            Gap(14)
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.Surface).border(1.dp, C.Border, RoundedCornerShape(20.dp))) {
                if (p.isVideo) {
                    AndroidView(
                        factory = { c ->
                            VideoView(c).apply {
                                setVideoPath(p.file)
                                setOnPreparedListener { mp -> mp.isLooping = true; start() }
                                setOnClickListener { if (isPlaying) pause() else start() }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().aspectRatio(p.w.toFloat() / p.h.toFloat())
                    )
                } else {
                    val bmp = remember(p.file) { try { ImageIO.loadPreview(p.file, 1600).asImageBitmap() } catch (e: Throwable) { null } }
                    if (bmp != null) Image(bmp, contentDescription = null, modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
                }
            }
            if (p.isVideo) {
                Gap(8)
                Text("Tap the video to pause or play.", style = ts(11, color = C.Muted, family = Mono))
            }
            Gap(10)
            Text(
                p.w.toString() + " × " + p.h + " px" + (if (p.isVideo) "  •  " + fmtTime(p.durationMs) else "") + "  •  original size kept",
                style = ts(12, color = C.Muted, family = Mono)
            )
            if (p.note.isNotBlank()) {
                Gap(8)
                Text(p.note, style = ts(12, color = C.Warn, line = 19))
            }
            Gap(24)
            PxButton(if (p.isVideo) "Download MP4" else "Download image", {
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { if (p.isVideo) Saver.saveVideo(ctx, File(p.file)) else Saver.saveImage(ctx, File(p.file)) }
                    Toast.makeText(
                        ctx,
                        if (ok) (if (p.isVideo) "Saved to Movies/remoit" else "Saved to Pictures/remoit") else "Could not save. Check the save permission and free space.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }, Modifier.fillMaxWidth())
            Gap(12)
            PxButton("New project", { vm.newProject() }, Modifier.fillMaxWidth(), secondary = true)
        }
        Gap(30)
    }
}

// ---------------------------------------------------------------- HISTORY

@Composable
fun HistoryScreen(vm: AppVm) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf(History.all()) }
    Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 10.dp)) {
        TopBar("History") { vm.back() }
        Gap(8)
        if (list.isEmpty()) {
            Gap(60)
            Text("No finished projects yet.", style = ts(15, color = C.Muted), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }
        list.forEach { p ->
            val thumb = remember(p.thumb) { if (p.thumb.isBlank()) null else BitmapFactory.decodeFile(p.thumb)?.asImageBitmap() }
            PxCard(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
                Column {
                    if (thumb != null) {
                        Image(thumb, contentDescription = null, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.FillWidth)
                        Gap(10)
                    }
                    Text(
                        (if (p.isVideo) "Video" else "Image") + "  •  ${p.w} × ${p.h}" + (if (p.isVideo) "  •  " + fmtTime(p.durationMs) else ""),
                        style = ts(14, FontWeight.Medium, line = 21)
                    )
                    Gap(4)
                    Text(SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(p.createdAt)), style = ts(11, color = C.Muted, family = Mono))
                    Gap(12)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PxButton("Open", { vm.result = p; vm.screen = Screen.RESULT }, Modifier.weight(1f))
                        PxButton("Save", {
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { if (p.isVideo) Saver.saveVideo(ctx, File(p.file)) else Saver.saveImage(ctx, File(p.file)) }
                                Toast.makeText(ctx, if (ok) "Saved to gallery" else "Could not save.", Toast.LENGTH_LONG).show()
                            }
                        }, Modifier.weight(1f), secondary = true)
                        PxButton("Delete", { History.remove(p); list = History.all() }, Modifier.weight(1f), secondary = true)
                    }
                }
            }
        }
        Gap(30)
    }
}

// ---------------------------------------------------------------- DIALOGS

@Composable
fun MessageDialog(text: String, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("OK", style = ts(14, FontWeight.SemiBold, C.Accent)) } },
        text = { Text(text, style = ts(14, color = C.Text, line = 22)) },
        containerColor = C.Surface
    )
}

@Composable
private fun SettingRow(title: String, sub: String, danger: Boolean = false, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(16.dp)).background(C.Surface2)
            .clickable(onClick = onClick).padding(16.dp)
    ) {
        Text(title, style = ts(15, FontWeight.SemiBold, if (danger) C.Danger else C.Text))
        Text(sub, style = ts(12, color = C.Muted, line = 18))
    }
}

@Composable
fun SettingsDialog(vm: AppVm, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var showAddon by remember { mutableStateOf(false) }
    var confirmRefresh by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onClose) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(C.Surface)
                .border(1.dp, C.Border, RoundedCornerShape(24.dp)).padding(20.dp)
        ) {
            Text("Settings", style = ts(22, FontWeight.Bold))
            Gap(14)
            SettingRow("History", "Your finished projects") { onClose(); vm.screen = Screen.HISTORY }
            SettingRow("Add-on", if (Credits.unlimited) "Unlimited credits are active" else "Enter an add-on code") { showAddon = true }
            SettingRow("Refresh", "Remove everything and restart the app fully", danger = true) { confirmRefresh = true }
            Gap(4)
            Text("remoit  v" + BuildConfig.VERSION_NAME, style = ts(11, color = C.Muted, family = Mono), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Gap(4)
            LinkText("Made by ${Texts.OWNER_IG}", ts(11, color = C.Muted, family = Mono), Modifier.fillMaxWidth())
        }
    }
    if (showAddon) AddonDialog(vm) { showAddon = false }
    if (confirmRefresh) {
        AlertDialog(
            onDismissRequest = { confirmRefresh = false },
            title = { Text("Refresh everything?", style = ts(18, FontWeight.Bold)) },
            text = {
                Text(
                    "This deletes all projects, results and saved settings, then restarts the app from the beginning. Your daily credit usage is kept.",
                    style = ts(14, color = C.Muted, line = 22)
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmRefresh = false; onClose(); vm.refreshApp(ctx) }) { Text("Refresh", style = ts(14, FontWeight.SemiBold, C.Danger)) }
            },
            dismissButton = { TextButton(onClick = { confirmRefresh = false }) { Text("Cancel", style = ts(14, color = C.Muted)) } },
            containerColor = C.Surface
        )
    }
}

@Composable
fun AddonDialog(vm: AppVm, onClose: () -> Unit) {
    var code by remember { mutableStateOf("") }
    var status by remember { mutableStateOf(if (Credits.unlimited) "✓ Unlimited credits are active on this device." else "") }
    Dialog(onDismissRequest = onClose) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(C.Surface)
                .border(1.dp, C.Border, RoundedCornerShape(24.dp)).padding(20.dp)
        ) {
            Text("Add-on", style = ts(22, FontWeight.Bold))
            Gap(6)
            Text("Have an add-on code? Enter it here.", style = ts(13, color = C.Muted, line = 20))
            Gap(14)
            OutlinedTextField(
                value = code, onValueChange = { code = it }, singleLine = true,
                label = { Text("Add-on code", style = ts(12, color = C.Muted)) },
                modifier = Modifier.fillMaxWidth(), textStyle = ts(14, family = Mono), shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = C.Accent, unfocusedBorderColor = C.Border,
                    focusedContainerColor = C.Surface2, unfocusedContainerColor = C.Surface2,
                    cursorColor = C.Accent, focusedTextColor = C.Text, unfocusedTextColor = C.Text
                )
            )
            if (status.isNotEmpty()) {
                Gap(8)
                Text(status, style = ts(12, color = if (status.startsWith("✓")) C.Good else C.Warn, line = 18))
            }
            Gap(14)
            PxButton("Apply code", {
                status = when {
                    Credits.unlimited -> "✓ Unlimited credits are active on this device."
                    code.isBlank() -> "Enter a code first."
                    Credits.redeem(code) -> { vm.refreshCredits(); code = ""; "✓ Code accepted. Unlimited credits unlocked." }
                    else -> "That code is not valid."
                }
            }, Modifier.fillMaxWidth())
            Gap(10)
            PxButton("Close", onClose, Modifier.fillMaxWidth(), secondary = true)
        }
    }
}
