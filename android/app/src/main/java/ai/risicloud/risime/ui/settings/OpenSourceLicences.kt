package ai.risicloud.risime.ui.settings

import androidx.compose.foundation.clickable
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
 * Settings → About → Legal → Third-party notices (decision 079, moved 2026-10-12): only the libraries bundled in
 * the app, grouped by licence: the licence name, each component's copyright line, then the licence text once.
 * No descriptions, nothing about the server, models or infrastructure. The texts are app assets
 * ([LICENCE_TEXT_ASSETS]); a unit test checks each one exists.
 */
data class OssComponent(val name: String, val holder: String, val licence: String) {
    val copyrightLine: String get() = "$name: Copyright © $holder"
}

data class NoticeGroup(val licence: String, val licenceName: String, val copyrightLines: List<String>)

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
    OssComponent("Android Jetpack (AndroidX)", "The Android Open Source Project", LICENCE_APACHE),
    OssComponent("Kotlin, kotlinx.coroutines, kotlinx.serialization", "JetBrains s.r.o. and Kotlin contributors", LICENCE_APACHE),
    OssComponent("OkHttp, Okio", "Square, Inc.", LICENCE_APACHE),
    OssComponent("libphonenumber", "Google LLC", LICENCE_APACHE),
    OssComponent("libphonenumber-android", "Michael Rozumyanskiy", LICENCE_APACHE),
    OssComponent("AppAuth for Android", "The OpenID Foundation", LICENCE_APACHE),
    OssComponent("Firebase Cloud Messaging SDK", "Google LLC", LICENCE_APACHE),
    OssComponent("LiveKit Android SDK", "LiveKit, Inc.", LICENCE_APACHE),
    OssComponent("JNA (Java Native Access)", "Timothy Wall and JNA contributors", LICENCE_APACHE),
    OssComponent("abseil-cpp", "The Abseil Authors", LICENCE_APACHE),
    OssComponent("BoringSSL", "The BoringSSL Authors", LICENCE_APACHE),
    OssComponent("WebRTC", "The WebRTC project authors", LICENCE_BSD3),
    OssComponent("Opus", "Xiph.Org Foundation and the Opus contributors", LICENCE_BSD3),
    OssComponent("libsrtp", "Cisco Systems, Inc.", LICENCE_BSD3),
    OssComponent("libyuv", "The LibYuv project authors", LICENCE_BSD3),
    OssComponent("usrsctp", "The usrsctp authors", LICENCE_BSD3),
    OssComponent("Protocol Buffers", "Google LLC", LICENCE_BSD3),
    OssComponent("ed25519-dalek", "isis agora lovecruft and the dalek-cryptography authors", LICENCE_BSD3),
    OssComponent("OpenMLS", "The OpenMLS authors", LICENCE_MIT),
    OssComponent("RustCrypto (aes-gcm, argon2, hkdf, sha2)", "The RustCrypto Project developers", LICENCE_MIT),
    OssComponent("serde, serde_json, thiserror, base64, getrandom, miniz_oxide, zeroize, unicode-normalization, unicode-segmentation", "Their respective authors", LICENCE_MIT),
    OssComponent("SecLists", "Daniel Miessler and SecLists contributors", LICENCE_MIT),
    OssComponent("UniFFI", "Mozilla Foundation", LICENCE_MPL2),
    OssComponent("Noto Sans, Noto Sans Sinhala, Noto Sans Tamil", "The Noto Project Authors", LICENCE_OFL),
)

/** [OSS_COMPONENTS] grouped by licence, in first-seen order; each licence appears exactly once. */
fun noticeGroups(components: List<OssComponent> = OSS_COMPONENTS): List<NoticeGroup> =
    components.groupBy { it.licence }.map { (id, cs) -> NoticeGroup(id, LICENCE_NAMES[id] ?: id, cs.map { it.copyrightLine }) }

const val LEGAL_TITLE = "Legal"
const val THIRD_PARTY_NOTICES_TITLE = "Third-party notices"

@Composable
fun ThirdPartyNoticesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val groups = remember { noticeGroups() }
    val texts = remember {
        groups.associate { g -> g.licence to (LICENCE_TEXT_ASSETS[g.licence]?.let { a -> runCatching { context.assets.open(a).bufferedReader().use { it.readText() } }.getOrNull() } ?: "") }
    }
    Scaffold(
        topBar = { RisiTopBar(title = THIRD_PARTY_NOTICES_TITLE, onBack = onBack) },
        contentWindowInsets = WindowInsets(0),
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp).testTag("third_party_notices")) {
            items(groups) { g ->
                Text(g.licenceName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
                g.copyrightLines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 4.dp)) }
                Text(texts[g.licence].orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                HorizontalDivider()
            }
        }
    }
}

/** Settings → About → Legal: for now only "Third-party notices" (ToS / Privacy come later). */
@Composable
fun LegalScreen(onBack: () -> Unit) {
    var notices by rememberSaveable { mutableStateOf(false) }
    if (notices) {
        androidx.activity.compose.BackHandler { notices = false }
        ThirdPartyNoticesScreen(onBack = { notices = false })
        return
    }
    Scaffold(
        topBar = { RisiTopBar(title = LEGAL_TITLE, onBack = onBack) },
        contentWindowInsets = WindowInsets(0),
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).testTag("legal")) {
            item {
                Text(
                    THIRD_PARTY_NOTICES_TITLE,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth().clickable { notices = true }.padding(16.dp).testTag("third_party_notices_open"),
                )
                HorizontalDivider()
            }
        }
    }
}

/** Full-screen dialog host for [LegalScreen] (no navigation route needed). */
@Composable
fun LegalDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        LegalScreen(onBack = onDismiss)
    }
}
