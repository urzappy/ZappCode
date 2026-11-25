package com.zapp.net.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zapp.net.MainViewModel
import com.zapp.net.ui.theme.Accent
import com.zapp.net.ui.theme.Border
import com.zapp.net.ui.theme.Danger
import com.zapp.net.ui.theme.Layer1
import com.zapp.net.ui.theme.TextDim
import com.zapp.net.ui.theme.TextPrimary

/**
 * First-run screen: paste the one-time pair link printed by `opencode pair`
 * (in Termux on this phone, the script prints it; on PC it prints in a terminal).
 * One tap -> cookie stored -> 30 days of access.
 */
@Composable
fun ConnectScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    var pairUrl by remember { mutableStateOf("") }
    val serverSaved by vm.serverBase.collectAsState()
    var serverBase by remember(serverSaved) { mutableStateOf(serverSaved) }
    // A loopback server's password is this install's own device token
    // (`Onboarding.ensureToken`), so the field is blank by default and the connect path
    // substitutes it. The field still exists for a remote server, which has its own.
    var token by remember(serverSaved) { mutableStateOf("") }
    val status by vm.status.collectAsState()
    val statusFailed by vm.statusFailed.collectAsState()
    val clipboard = LocalClipboardManager.current
    val clipboardText = clipboard.getText()?.text.orEmpty()

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("ZAPP-Net", fontSize = 34.sp, color = Accent)
        Spacer(Modifier.height(6.dp))
        Text("your opencode, your rotation, one phone", fontSize = 13.sp, color = TextDim)
        Spacer(Modifier.height(28.dp))

        OutlinedTextField(
            value = serverBase,
            onValueChange = { serverBase = it },
            placeholder = { Text("server address", color = TextDim, fontSize = 13.sp) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Accent,
                unfocusedBorderColor = Border,
            ),
            singleLine = true,
        )
        Text(
            "this phone (Termux): 127.0.0.1:4096 · PC on your LAN: that PC's IP :28894",
            fontSize = 10.sp, color = TextDim,
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            placeholder = { Text("this phone: automatic · remote: its password", color = TextDim, fontSize = 13.sp) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Accent,
                unfocusedBorderColor = Border,
            ),
            singleLine = true,
        )
        Text(
            "phone-only: address + this install's device token. No pair link, no account.",
            fontSize = 10.sp, color = TextDim,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = pairUrl,
            onValueChange = { pairUrl = it },
            placeholder = { Text("paste pair link (opencode pair)", color = TextDim, fontSize = 13.sp) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Accent,
                unfocusedBorderColor = Border,
            ),
            singleLine = true,
        )
        // Termux: termux-clipboard-set "http://127.0.0.1:PORT/auth/connect/..." -> one tap here
        if (clipboardText.contains("/auth/connect/")) {
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { pairUrl = clipboardText.trim() },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Layer1, contentColor = TextPrimary),
            ) { Text("Use link from clipboard", fontSize = 13.sp) }
        }
        Spacer(Modifier.height(12.dp))
        // What the engine is actually doing, before he taps. Starting it is the app's job now,
        // so the screen has to say whether it is up, starting, or not installed at all.
        EngineCard(vm)
        Spacer(Modifier.height(12.dp))
        // A blank pair field used to fall through to the pair parser and surface OkHttp's
        // "Expected URL scheme 'http' or 'https'" — a parser error that reads like a dead
        // server. Phone-only path (address + password) is the default now.
        val base = normBase(serverBase)
        val hasPair = pairUrl.isNotBlank()
        val connecting by vm.connecting.collectAsState()
        Button(
            onClick = {
                if (hasPair) {
                    vm.pairAndConnect(base, pairUrl.trim())
                } else {
                    // A loopback server's password is the app's own device token, so an
                    // empty field there is not "no credential" - it is "the one this
                    // install already owns". A remote base still needs what was typed.
                    val effective = token.trim().ifBlank {
                        if (base.contains("127.0.0.1") || base.contains("localhost")) {
                            com.zapp.net.onboarding.Onboarding.ensureToken(ctx)
                        } else {
                            ""
                        }
                    }
                    vm.connectWithToken(base, effective)
                }
            },
            enabled = !connecting,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
        ) {
            // The label follows the work. A button that reads "Connect" while a 2 s round
            // trip is in flight is what makes a working app feel dead.
            Text(
                when {
                    connecting && hasPair -> "Pairing…"
                    connecting -> "Verifying…"
                    else -> if (hasPair) "Pair and connect" else "Connect to this phone"
                },
            )
        }

        Spacer(Modifier.height(16.dp))
        Text(
            status,
            fontSize = 12.sp,
            // Same rule as the chat strip: the writer says whether it failed.
            color = if (statusFailed) Danger else TextDim,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** `127.0.0.1:4096` and `http://127.0.0.1:4096/` both have to reach OkHttp as a URL. */
private fun normBase(raw: String): String {
    val s = raw.trim().ifBlank { com.zapp.net.settings.PHONE_SERVER }
    val scheme = if (s.startsWith("http://") || s.startsWith("https://")) s else "http://$s"
    return scheme.trimEnd('/')
}
