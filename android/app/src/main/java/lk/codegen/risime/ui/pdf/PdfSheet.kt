package lk.codegen.risime.ui.pdf

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import lk.codegen.risime.AppContainer
import lk.codegen.risime.ui.chat.FileActions
import lk.codegen.risime.ui.chat.FileStrings
import lk.codegen.risime.ui.chat.TargetPickerSheet
import lk.codegen.risime.ui.share.ShareItem
import lk.codegen.risime.ui.share.ShareRegistry
import lk.codegen.risime.ui.theme.Spacing

/** v1.34 §33.14 sheet strings. */
object PdfStrings {
    const val MAKING = "Making the PDF…"
    const val SHARE = "Share"
    const val SAVE = "Save to Downloads"
    const val SEND = "Send to chat…"
    const val SEND_TITLE = "Send PDF to…"
    fun sent(n: Int) = if (n == 1) "PDF sent to 1 chat" else "PDF sent to $n chats"
    fun pages(n: Int) = if (n == 1) "1 page" else "$n pages"
}

/**
 * §33.14 the root's host: [AppContainer.pdfRequest] → generate on the phone → a bottom sheet with a
 * preview of page 1 and [Share] / [Save to Downloads] / [Send to chat…]. The plaintext PDF is deleted when
 * the sheet closes (the share stream holds its own copy in memory), after 1 hour and on the next start.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfExportHost(c: AppContainer) {
    val request by c.pdfRequest.collectAsState()
    val source = request ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var outcome by remember(source) { mutableStateOf<PdfExport.Outcome?>(null) }
    var picking by remember(source) { mutableStateOf(false) }
    var note by remember(source) { mutableStateOf<String?>(null) }
    LaunchedEffect(source) { outcome = PdfExport.make(context, c, source) }
    fun close() {
        (outcome as? PdfExport.Outcome.Ok)?.made?.file?.delete()
        c.pdfRequest.value = null
    }
    val o = outcome
    if (o is PdfExport.Outcome.Failed) {
        AlertDialog(onDismissRequest = ::close, text = { Text(o.text) }, confirmButton = { TextButton(onClick = ::close) { Text("OK") } })
        return
    }
    val createDoc = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val made = (outcome as? PdfExport.Outcome.Ok)?.made ?: return@rememberLauncherForActivityResult
        if (uri != null) {
            val ok = runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(made.file.readBytes()) } != null }.getOrDefault(false)
            note = if (ok) FileStrings.SAVED else FileStrings.CORRUPT
        }
    }
    ModalBottomSheet(onDismissRequest = ::close) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Spacing.lg).testTag("pdf_sheet")) {
            if (o == null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = Spacing.lg)) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Spacer(Modifier.size(Spacing.md))
                    Text(PdfStrings.MAKING)
                }
                return@Column
            }
            val made = (o as PdfExport.Outcome.Ok).made
            val bmp = remember(made.file) { PdfExport.preview(made.file, 480) }
            bmp?.let {
                Image(
                    it.asImageBitmap(), "Page 1",
                    Modifier.fillMaxWidth().height(260.dp).border(0.5.dp, MaterialTheme.colorScheme.outlineVariant).testTag("pdf_preview"),
                )
            }
            Spacer(Modifier.size(Spacing.sm))
            Text(made.fileName, style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("pdf_name"))
            Text(PdfStrings.pages(made.pages), style = MaterialTheme.typography.labelMedium)
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            Row(Modifier.fillMaxWidth().padding(vertical = Spacing.sm), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = {
                    // §33.10: streamed through the ShareProvider (a copy in memory; nothing else on disk).
                    val bytes = made.file.readBytes()
                    val uri = ShareRegistry.register(context.packageName, ShareItem(made.fileName, "application/pdf", bytes.size.toLong(), producer = { it.write(bytes) }, expiresAt = ShareRegistry.clock() + ShareRegistry.TTL_MS))
                    val send = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    send.clipData = android.content.ClipData.newRawUri(null, uri)
                    runCatching { context.startActivity(Intent.createChooser(send, null)) }
                    lk.codegen.risime.ui.share.ShareIntents.scheduleRevoke(context, listOf(uri))
                }, modifier = Modifier.testTag("pdf_share")) { Text(PdfStrings.SHARE) }
                TextButton(onClick = {
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        note = if (FileActions.saveBytesToDownloads(context, made.file.readBytes(), made.fileName, "application/pdf")) FileStrings.SAVED else FileStrings.CORRUPT
                    } else {
                        createDoc.launch(made.fileName)
                    }
                }, modifier = Modifier.testTag("pdf_save")) { Text(PdfStrings.SAVE) }
                TextButton(onClick = { picking = true }, modifier = Modifier.testTag("pdf_send")) { Text(PdfStrings.SEND) }
            }
        }
    }
    val made = (o as? PdfExport.Outcome.Ok)?.made
    if (picking && made != null) {
        // §33.8: no hint for a Risi source (that content is already Official).
        TargetPickerSheet(
            c, hasImage = false, hasFile = true, max = lk.codegen.risime.data.messaging.SelectionRules.MAX_TARGETS, sourcePrivate = false,
            title = PdfStrings.SEND_TITLE, allowMessage = false,
            onSend = { targets, _ -> PdfExport.sendTo(c, made, targets).size },
            onDone = { n -> picking = false; note = PdfStrings.sent(n) },
            onDismiss = { picking = false },
        )
    }
}
