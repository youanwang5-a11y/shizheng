package app.shizheng.ui

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.createBitmap
import app.shizheng.R
import app.shizheng.data.Evidence
import app.shizheng.data.EvidenceKind
import app.shizheng.data.EvidenceStore
import app.shizheng.data.decodePhoto
import app.shizheng.data.openPdf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal data class EvidenceActions(val camera: () -> Unit, val photos: () -> Unit, val files: () -> Unit)

@Composable
internal fun EvidenceThumbnail(
    store: EvidenceStore, item: Evidence, modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null, onLongClick: (() -> Unit)? = null
) {
    val bitmap by produceState<Bitmap?>(null, item.id) {
        value = if (item.kind == EvidenceKind.PHOTO) withContext(Dispatchers.IO) {
            runCatching { decodePhoto(store.file(item), 160) }.getOrNull()
        } else null
    }
    val click = if (onClick != null) Modifier.combinedClickable(
        role = Role.Button, onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "选择记录"
    ).semantics { contentDescription = "预览${item.kind.label} · ${item.name}" } else Modifier
    Surface(
        modifier.size(47.dp), shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Box(Modifier.fillMaxSize().then(click), contentAlignment = Alignment.Center) {
            val image = bitmap
            when {
                image != null -> Image(image.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                item.kind == EvidenceKind.PHOTO -> Icon(painterResource(R.drawable.ic_photo), null, Modifier.size(23.dp))
                item.kind == EvidenceKind.PDF -> FileMark(
                    R.drawable.ic_pdf_document, "PDF", Alignment.BottomCenter
                )
                else -> FileMark(R.drawable.ic_word_document, "W", Alignment.TopCenter)
            }
        }
    }
}

@Composable
private fun FileMark(icon: Int, label: String, badgeAlignment: Alignment) {
    Box(Modifier.size(31.dp).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), null, Modifier.size(27.dp), tint = MaterialTheme.colorScheme.secondary)
        Surface(
            Modifier.align(badgeAlignment), shape = MaterialTheme.shapes.extraSmall,
            color = MaterialTheme.colorScheme.secondary, contentColor = MaterialTheme.colorScheme.onSecondary
        ) {
            Text(
                label, Modifier.padding(horizontal = 4.dp), fontWeight = FontWeight.Bold,
                fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1
            )
        }
    }
}

@Composable
internal fun AttachmentRow(store: EvidenceStore, item: Evidence, click: () -> Unit) {
    Surface(onClick = click, color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            EvidenceThumbnail(store, item)
            Column(Modifier.weight(1f)) {
                Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                Help(item.kind.label)
            }
            Text("›", Modifier.padding(end = 5.dp), color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
internal fun EvidencePreview(state: AppState, item: Evidence, exportFile: (Evidence) -> Unit) {
    val file = state.repository.evidence.file(item)
    when (item.kind) {
        EvidenceKind.PHOTO -> {
            val preview by produceState<Result<Bitmap>?>(null, item.id) {
                value = withContext(Dispatchers.IO) { runCatching { decodePhoto(file, 2500) } }
            }
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(item.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Help("双指缩放，双击切换缩放。")
                Box(Modifier.weight(1f).fillMaxWidth()) { Display(preview, "照片证明") }
                SecondaryButton({ exportFile(item) }, Modifier.fillMaxWidth()) { Text("另存照片") }
            }
        }
        EvidenceKind.PDF -> PdfPreview(file, item.name) { exportFile(item) }
        EvidenceKind.DOC, EvidenceKind.DOCX -> Page {
            Panel {
                Heading(item.name)
                Help("可交由手机上的 Word 兼容应用打开，也可另存原文件。")
                PrimaryButton({ state.openWord(item) }, Modifier.fillMaxWidth()) { Text("用其他应用打开") }
                SecondaryButton({ exportFile(item) }, Modifier.fillMaxWidth()) { Text("另存 Word 文件") }
            }
        }
    }
}

@Composable
private fun PdfPreview(file: File, name: String, exportFile: () -> Unit) {
    var index by remember(file) { mutableIntStateOf(0) }
    var pageCount by remember(file) { mutableIntStateOf(0) }
    val preview by produceState<Result<Bitmap>?>(null, file, index) {
        value = null
        val rendered = withContext(Dispatchers.IO) { runCatching { renderPdf(file, index) } }
        pageCount = rendered.getOrNull()?.first ?: pageCount
        value = rendered.map { it.second }
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            key(index) { Display(preview, "PDF 第 ${index + 1} 页") }
        }
        if (pageCount > 0) Row(
            Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton({ index-- }, enabled = index > 0) { Text("上一页") }
            Text("${index + 1} / $pageCount")
            TextButton({ index++ }, enabled = index + 1 < pageCount) { Text("下一页") }
        }
        SecondaryButton(exportFile, Modifier.fillMaxWidth()) { Text("另存 PDF") }
    }
}

private fun renderPdf(file: File, index: Int): Pair<Int, Bitmap> {
    return openPdf(file).use {
        it.openPage(index).use { page ->
            val scale = 1700.0 / maxOf(page.width, page.height)
            val bitmap = createBitmap(
                (page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1),
                Bitmap.Config.ARGB_8888
            )
            bitmap.eraseColor(android.graphics.Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            it.pageCount to bitmap
        }
    }
}

@Composable
private fun Display(result: Result<Bitmap>?, description: String) {
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center) {
        when {
            result == null -> CircularProgressIndicator()
            result.isFailure -> Help("无法查看这个文件。文件可能已损坏，或格式无法读取。")
            else -> {
                val bitmap = result.getOrThrow()
                var scale by remember(bitmap) { mutableFloatStateOf(1f) }
                var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
                val transform = rememberTransformableState { zoom, pan, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    val x = constraints.maxWidth * (scale - 1) / 2
                    val y = constraints.maxHeight * (scale - 1) / 2
                    offset = Offset((offset.x + pan.x).coerceIn(-x, x), (offset.y + pan.y).coerceIn(-y, y))
                }
                Image(
                    bitmap.asImageBitmap(), contentDescription = description,
                    modifier = Modifier.fillMaxSize().pointerInput(bitmap) {
                        detectTapGestures(onDoubleTap = { scale = if (scale > 1) 1f else 2.5f; offset = Offset.Zero })
                    }.transformable(transform).graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
                    contentScale = ContentScale.Fit
                )
            }
        }
    }
}
