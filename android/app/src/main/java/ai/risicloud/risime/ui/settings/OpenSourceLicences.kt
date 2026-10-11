package ai.risicloud.risime.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ai.risicloud.risime.ui.common.RisiTopBar

/**
 * Settings → About → Open-source licences (decision 079): every third-party component RisiMe ships, with its
 * licence. The licence texts are app assets ([LICENCE_TEXT_ASSETS]); a unit test checks each one exists.
 */
data class OssComponent(val name: String, val holder: String, val licence: String)

const val LICENCE_APACHE = "Apache-2.0"
const val LICENCE_MIT = "MIT"
const val LICENCE_BSD3 = "BSD-3-Clause"
const val LICENCE_MPL2 = "MPL-2.0"
const val LICENCE_OFL = "OFL-1.1"

/** Licence id → the asset with its full text. */
val LICENCE_TEXT_ASSETS: Map<String, String> = mapOf(
    LICENCE_APACHE to "licences/Apache-2.0.txt",
    LICENCE_MIT to "licences/MIT.txt",
    LICENCE_BSD3 to "licences/BSD-3-Clause.txt",
    LICENCE_MPL2 to "licences/MPL-2.0.txt",
    LICENCE_OFL to "fonts/OFL.txt",
)

val LICENCE_NAMES: Map<String, String> = mapOf(
    LICENCE_APACHE to "Apache License 2.0",
    LICENCE_MIT to "MIT License",
    LICENCE_BSD3 to "BSD 3-Clause License",
    LICENCE_MPL2 to "Mozilla Public License 2.0",
    LICENCE_OFL to "SIL Open Font License 1.1",
)

val OSS_COMPONENTS: List<OssComponent> = listOf(
    OssComponent("Android Jetpack (AndroidX: Compose, Material 3, Room, DataStore, WorkManager, Navigation, Biometric, Emoji2, Core Telecom)", "The Android Open Source Project", LICENCE_APACHE),
    OssComponent("Kotlin, kotlinx.coroutines, kotlinx.serialization", "JetBrains s.r.o. and Kotlin contributors", LICENCE_APACHE),
    OssComponent("OkHttp, Okio", "Square, Inc.", LICENCE_APACHE),
    OssComponent("libphonenumber (libphonenumber-android)", "Google LLC; Michael Rozumyanskiy", LICENCE_APACHE),
    OssComponent("AppAuth for Android", "The OpenID Foundation", LICENCE_APACHE),
    OssComponent("Firebase Cloud Messaging SDK", "Google LLC", LICENCE_APACHE),
    OssComponent("LiveKit Android SDK", "LiveKit, Inc.", LICENCE_APACHE),
    OssComponent("WebRTC (LiveKit / webrtc-sdk Android build)", "The WebRTC project authors", LICENCE_BSD3),
    OssComponent("Protocol Buffers (protobuf-javalite)", "Google LLC", LICENCE_BSD3),
    OssComponent("JNA (Java Native Access)", "Timothy Wall and JNA contributors", LICENCE_APACHE),
    OssComponent("OpenMLS", "The OpenMLS authors", LICENCE_MIT),
    OssComponent("UniFFI", "Mozilla Foundation", LICENCE_MPL2),
    OssComponent("RustCrypto (aes-gcm, argon2, hkdf, sha2)", "The RustCrypto Project developers", LICENCE_MIT),
    OssComponent("ed25519-dalek", "isis agora lovecruft and the dalek-cryptography authors", LICENCE_BSD3),
    OssComponent("serde, serde_json, thiserror, base64, getrandom, miniz_oxide, zeroize, unicode-normalization, unicode-segmentation", "Their respective authors", LICENCE_MIT),
    OssComponent("Noto Sans, Noto Sans Sinhala, Noto Sans Tamil (fonts in exported PDFs)", "The Noto Project Authors", LICENCE_OFL),
    OssComponent("SecLists common-password list (recovery-key check)", "Daniel Miessler and SecLists contributors", LICENCE_MIT),
)

const val OPEN_SOURCE_LICENCES_TITLE = "Open-source licences"

@Composable
fun OpenSourceLicencesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val text = remember(open) {
        open?.let { id -> LICENCE_TEXT_ASSETS[id]?.let { a -> runCatching { context.assets.open(a).bufferedReader().use { it.readText() } }.getOrNull() } }
    }
    androidx.activity.compose.BackHandler(enabled = open != null) { open = null }
    Scaffold(
        topBar = { RisiTopBar(title = open?.let { LICENCE_NAMES[it] } ?: OPEN_SOURCE_LICENCES_TITLE, onBack = { if (open != null) open = null else onBack() }) },
        contentWindowInsets = WindowInsets(0),
    ) { pad ->
        if (open != null) {
            LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp).testTag("licence_text")) {
                item { Text(text ?: "", style = MaterialTheme.typography.bodySmall) }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(pad).testTag("oss_list")) {
                item {
                    Text(
                        "RisiMe is proprietary software, © 2026 Risi. It includes the open-source components below; tap one to read its licence.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                items(OSS_COMPONENTS) { c ->
                    Column(Modifier.fillMaxWidth().clickable { open = c.licence }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text(c.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text("${c.holder} · ${LICENCE_NAMES[c.licence] ?: c.licence}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

/** Full-screen dialog host for [OpenSourceLicencesScreen] (no navigation route needed). */
@Composable
fun OpenSourceLicencesDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        OpenSourceLicencesScreen(onBack = onDismiss)
    }
}
