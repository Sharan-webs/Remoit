package com.remoit.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Page that comes right after the permission page: add-on code box. */
@Composable
fun SetupScreen(vm: AppVm) {
    var code by remember { mutableStateOf("") }
    var codeStatus by remember { mutableStateOf(if (Credits.unlimited) "✓ Unlimited credits are active on this device." else "") }

    fun finish() {
        if (code.trim().isNotEmpty() && !Credits.unlimited) Credits.redeem(code)
        Store.setupDone = true
        vm.refreshCredits()
        vm.screen = Screen.BOOT
    }

    Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 16.dp)) {
        Logo(22)
        Gap(26)
        Text("Setup", style = ts(34, FontWeight.Bold))
        Gap(8)
        Text(
            "Enter an add-on code if you have one. It is optional, and you can also enter it later in Settings.",
            style = ts(14, color = C.Muted, line = 23)
        )
        Gap(24)

        Text("Add-on", style = ts(15, FontWeight.SemiBold))
        Gap(4)
        Text("Have an add-on code? Enter it here.", style = ts(12, color = C.Muted, line = 19))
        Gap(10)
        OutlinedTextField(
            value = code, onValueChange = { code = it; if (!Credits.unlimited) codeStatus = "" }, singleLine = true,
            label = { Text("Add-on code", style = ts(12, color = C.Muted)) },
            modifier = Modifier.fillMaxWidth(),
            textStyle = ts(14, family = Mono), shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = C.Accent, unfocusedBorderColor = C.Border,
                focusedContainerColor = C.Surface2, unfocusedContainerColor = C.Surface2,
                cursorColor = C.Accent, focusedTextColor = C.Text, unfocusedTextColor = C.Text
            )
        )
        if (codeStatus.isNotEmpty()) {
            Gap(8)
            Text(codeStatus, style = ts(12, color = if (codeStatus.startsWith("✓")) C.Good else C.Warn, line = 18))
        }
        Gap(10)
        PxButton("Apply code", {
            codeStatus = when {
                Credits.unlimited -> "✓ Unlimited credits are active on this device."
                code.isBlank() -> "Enter a code first."
                Credits.redeem(code) -> { vm.refreshCredits(); code = ""; "✓ Code accepted. Unlimited credits unlocked." }
                else -> "That code is not valid."
            }
        }, Modifier.fillMaxWidth(), secondary = true)

        Gap(28)
        PxButton("Continue", { finish() }, Modifier.fillMaxWidth())
        Gap(10)
        Text(
            "Skip for now", style = ts(13, color = C.Muted),
            modifier = Modifier.fillMaxWidth().clickable { Store.setupDone = true; vm.screen = Screen.BOOT }.padding(vertical = 8.dp),
            textAlign = TextAlign.Center
        )
        Gap(30)
    }
}
