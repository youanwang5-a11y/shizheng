package app.shizheng.data

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

class EvidenceStore(private val context: Context) {
    private val root = File(context.filesDir, "evidence").apply { mkdirs() }
    private val cameraDir = File(context.cacheDir, "camera").apply { mkdirs() }

    fun file(item: Evidence): File {
        require(validId(item.id))
        return File(root, "${item.id}.${item.kind.extension}")
    }

    fun import(recordId: String, uri: Uri, order: Int, camera: Boolean = false): Evidence {
        val resolver = context.contentResolver
        val name = if (camera) "照片_${java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))}.jpg"
        else resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "证明"
        val mime = resolver.getType(uri)
        val kind = evidenceKind(name, mime) ?: throw UserError("支持照片、PDF、DOC 和 DOCX。")
        val item = Evidence(newId(), recordId, cleanFileName(name, kind), kind, order, 0L, "")
        val target = file(item)
        try {
            val input = resolver.openInputStream(uri) ?: throw UserError("文件不可读。")
            val digest = input.use { source ->
                target.outputStream().use { output ->
                    copyAndHash(source, output, ATTACHMENT_LIMIT).also { output.fd.sync() }
                }
            }
            checkEvidenceFile(target, kind)
            return item.copy(size = digest.size, sha256 = digest.sha256)
        } catch (error: Exception) {
            target.delete()
            throw error
        }
    }

    fun remove(item: Evidence) { file(item).delete() }

    fun clearUnreferenced(retained: List<Evidence>) {
        val names = retained.mapTo(hashSetOf()) { file(it).name }
        root.listFiles()?.filter { it.isFile && it.name !in names }?.forEach { it.delete() }
    }

    fun cameraFile(): File = File.createTempFile("capture-", ".jpg", cameraDir)
    fun uri(file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    fun share(item: Evidence) {
        val target = file(item)
        if (!target.isFile || !target.canRead()) throw UserError("应用内的附件不可读。")
        // FileProvider grants a read-only URI for this one file.
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", target, item.name)
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
            .setDataAndType(uri, item.kind.mime)
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            throw UserError("未找到能打开 Word 的应用。可以先另存文件，再安装兼容应用。")
        }
    }

    fun copyTo(item: Evidence, uri: Uri) {
        val target = file(item)
        if (!target.isFile || !target.canRead()) throw UserError("应用内的附件不可读。")
        context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
            target.inputStream().use { it.copyTo(output) }
        } ?: throw UserError("无法写入选中的位置。")
    }

    fun photoForRecognition(item: Evidence): ByteArray {
        if (item.kind != EvidenceKind.PHOTO) throw UserError("请选择照片。")
        val preview = decodePhoto(file(item), 2000)
        return try {
            ByteArrayOutputStream().use { output ->
                if (!preview.compress(Bitmap.CompressFormat.JPEG, 88, output)) throw UserError("照片不可读，请重新选择。")
                output.toByteArray()
            }
        } finally {
            preview.recycle()
        }
    }

    fun exportNameAndType(item: Evidence): Pair<String, String> {
        val source = file(item)
        if (!source.isFile || !source.canRead()) throw UserError("应用内的附件不可读。")
        val mime = if (item.kind == EvidenceKind.PHOTO) {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.path, options)
            options.outMimeType ?: "application/octet-stream"
        } else item.kind.mime
        val extensions = when (mime) {
            "image/jpeg" -> listOf("jpg", "jpeg")
            "image/heif", "image/heic" -> listOf("heic", "heif")
            "image/x-ms-bmp" -> listOf("bmp")
            "application/octet-stream" -> emptyList()
            else -> listOf(if (item.kind == EvidenceKind.PHOTO) mime.substringAfter('/') else item.kind.extension)
        }
        val current = item.name.substringAfterLast('.', "")
        val name = if (extensions.isEmpty() || extensions.any { it.equals(current, ignoreCase = true) }) item.name
            else item.name + "." + extensions.first()
        return name to mime
    }
}

fun cleanFileName(name: String, kind: EvidenceKind): String =
    name.filterNot { it == '/' || it == '\\' || it.isISOControl() }.trim().take(FILE_NAME_LIMIT)
        .ifBlank { "证明.${kind.extension}" }

private fun evidenceKind(name: String, mime: String?): EvidenceKind? = when {
    mime == EvidenceKind.PDF.mime || name.endsWith(".pdf", true) -> EvidenceKind.PDF
    mime == EvidenceKind.DOCX.mime || name.endsWith(".docx", true) -> EvidenceKind.DOCX
    mime == EvidenceKind.DOC.mime || name.endsWith(".doc", true) -> EvidenceKind.DOC
    mime?.startsWith("image/") == true || name.substringAfterLast('.').lowercase() in
        setOf("jpg", "jpeg", "png", "heic", "heif", "webp", "gif", "bmp", "avif") -> EvidenceKind.PHOTO
    else -> null
}

fun checkEvidenceFile(file: File, kind: EvidenceKind) {
    val valid = when (kind) {
        EvidenceKind.PHOTO -> runCatching { decodePhoto(file, 128) }.getOrNull()?.let { it.recycle(); true } ?: false
        EvidenceKind.PDF -> runCatching { openPdf(file).use { it.pageCount > 0 } }.getOrDefault(false)
        EvidenceKind.DOC -> file.inputStream().use {
            it.readNBytesCompat(8).contentEquals(byteArrayOf(-48, -49, 17, -32, -95, -79, 26, -31))
        }
        EvidenceKind.DOCX -> runCatching {
            ZipFile(file).use { it.getEntry("[Content_Types].xml") != null && it.getEntry("word/document.xml") != null }
        }.getOrDefault(false)
    }
    if (!valid) throw UserError("文件内容不可读，或与所选类型不符。")
}

fun decodePhoto(file: File, maxSide: Int): Bitmap =
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
        val side = maxOf(info.size.width, info.size.height)
        if (side > maxSide) {
            decoder.setTargetSize(
                maxOf(1, (info.size.width.toLong() * maxSide / side).toInt()),
                maxOf(1, (info.size.height.toLong() * maxSide / side).toInt())
            )
        }
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }

private fun InputStream.readNBytesCompat(count: Int): ByteArray {
    val data = ByteArray(count)
    var offset = 0
    while (offset < count) {
        val read = read(data, offset, count - offset)
        if (read < 0) break
        offset += read
    }
    return data.copyOf(offset)
}

fun openPdf(file: File): PdfRenderer {
    val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    return try {
        PdfRenderer(descriptor)
    } catch (error: Exception) {
        descriptor.close()
        throw error
    }
}

data class ContentDigest(val size: Long, val sha256: String)

fun copyAndHash(input: InputStream, output: OutputStream, limit: Long): ContentDigest {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    var size = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        size += read
        if (size > limit) throw UserError("文件超过大小限制。")
        output.write(buffer, 0, read)
        digest.update(buffer, 0, read)
    }
    if (size == 0L) throw UserError("文件为空。")
    return ContentDigest(size, digest.digest().joinToString("") { "%02x".format(it) })
}
