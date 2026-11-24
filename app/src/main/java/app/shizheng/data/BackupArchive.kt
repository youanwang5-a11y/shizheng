package app.shizheng.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@Serializable
private data class BackupManifest(val format: String, val version: Int, val data: Snapshot)

class PreparedBackup(val snapshot: Snapshot, private val directory: File) : AutoCloseable {
    fun attachment(item: Evidence): File = File(directory, item.id)
    override fun close() { directory.deleteRecursively() }
}

class BackupArchive(private val validateContent: (File, EvidenceKind) -> Unit) {
    private val json = Json { encodeDefaults = true }

    fun write(data: Snapshot, output: OutputStream, file: (Evidence) -> File) {
        validate(data)
        val manifest = json.encodeToString(BackupManifest(FORMAT, VERSION, data)).encodeToByteArray()
        if (manifest.size > MANIFEST_LIMIT) throw UserError("备份清单超过大小限制。")
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(manifest)
            zip.closeEntry()
            for (item in data.evidence) {
                zip.putNextEntry(ZipEntry(entryName(item)))
                val digest = file(item).inputStream().use { copyAndHash(it, zip, item.size) }
                if (digest != ContentDigest(item.size, item.sha256)) throw UserError("附件内容已损坏，无法备份。")
                zip.closeEntry()
            }
        }
    }

    fun prepare(input: InputStream, directory: File): PreparedBackup {
        val staging = File(directory, newId())
        if (!staging.mkdirs()) throw IOException("Restore working directory could not be created")
        val archive = File(staging, "archive.zip")
        try {
            archive.outputStream().use { copyAndHash(input, it, TOTAL_LIMIT + 32L * 1024 * 1024) }
            val expectedEntries = checkDirectory(archive)
            val snapshot = ZipFile(archive).use { zip ->
                if (zip.size() != expectedEntries) invalid()
                val entries = mutableMapOf<String, ZipEntry>()
                val iterator = zip.entries()
                while (iterator.hasMoreElements()) {
                    val entry = iterator.nextElement()
                    if (entries.size >= LIST_LIMIT + 1 || entry.isDirectory ||
                        (entry.name != MANIFEST && !validEntry(entry.name)) ||
                        entries.put(entry.name, entry) != null
                    ) {
                        invalid()
                    }
                }
                val manifest = entries.remove(MANIFEST) ?: invalid()
                if (manifest.size !in 1..MANIFEST_LIMIT) invalid()
                val manifestBytes = java.io.ByteArrayOutputStream()
                zip.getInputStream(manifest).use { copyAndHash(it, manifestBytes, manifest.size) }
                if (manifestBytes.size().toLong() != manifest.size) invalid()
                if (CRC32().apply { update(manifestBytes.toByteArray()) }.value != manifest.crc) invalid()
                val tree = json.parseToJsonElement(manifestBytes.toString("UTF-8")).jsonObject
                if (tree["format"]?.jsonPrimitive?.content != FORMAT) invalid()
                val version = tree["version"]?.jsonPrimitive?.intOrNull ?: invalid()
                if (version != VERSION) throw UserError("这个备份格式版本暂不支持。")
                val data = json.decodeFromJsonElement<BackupManifest>(tree).data
                validate(data)
                if (entries.keys != data.evidence.mapTo(hashSetOf(), ::entryName)) invalid()
                for (item in data.evidence) {
                    val entry = entries.getValue(entryName(item))
                    if (entry.size != item.size) invalid()
                    val target = File(staging, item.id)
                    val digest = zip.getInputStream(entry).use { source ->
                        target.outputStream().use { out ->
                            copyAndHash(source, out, item.size).also { out.fd.sync() }
                        }
                    }
                    if (digest != ContentDigest(item.size, item.sha256)) invalid()
                    validateContent(target, item.kind)
                }
                data
            }
            archive.delete()
            return PreparedBackup(snapshot, staging)
        } catch (error: Exception) {
            staging.deleteRecursively()
            if (error is UserError) throw error
            throw UserError("无法读取备份。请确认文件完整且来自拾证。")
        }
    }

    // Format v1 writes an ordinary, single-disk ZIP without a comment or ZIP64 records.
    // Bound the central directory before ZipFile allocates its entry index.
    private fun checkDirectory(file: File): Int = RandomAccessFile(file, "r").use { input ->
        val footer = ByteArray(22)
        val footerOffset = input.length() - footer.size
        if (footerOffset < 0) invalid()
        input.seek(footerOffset)
        input.readFully(footer)
        val end = ByteBuffer.wrap(footer).order(ByteOrder.LITTLE_ENDIAN)
        if (end.int != 0x06054b50 || end.short.toInt() != 0 || end.short.toInt() != 0) invalid()
        val onDisk = end.short.toInt() and 0xffff
        val count = end.short.toInt() and 0xffff
        val size = end.int.toLong() and 0xffffffffL
        val offset = end.int.toLong() and 0xffffffffL
        if (count !in 1..(LIST_LIMIT + 1) || onDisk != count || end.short.toInt() != 0 ||
            size !in 46..(3L * 1024 * 1024) || offset + size != footerOffset
        ) {
            invalid()
        }
        count
    }

    private fun validate(data: Snapshot) {
        if (data.terms.size > LIST_LIMIT || data.records.size > LIST_LIMIT || data.evidence.size > LIST_LIMIT) invalid()
        if (data.terms.count(Term::current) > 1) invalid()
        val terms = hashSetOf<String>()
        for (term in data.terms) {
            val start = parseDate(term.start) ?: invalid()
            val end = parseDate(term.end) ?: invalid()
            if (!validId(term.id) || !terms.add(term.id) || term.name.isBlank() ||
                term.name.length > TERM_LIMIT || end < start
            ) {
                invalid()
            }
        }
        val records = hashSetOf<String>()
        for (record in data.records) {
            val start = parseDate(record.start) ?: invalid()
            val end = record.end?.let { parseDate(it) ?: invalid() } ?: start
            if (!validId(record.id) || !records.add(record.id) || end < start ||
                record.title.isBlank() || record.title.length > TITLE_LIMIT ||
                listOf(record.organization, record.notes, record.result).any { it.length > TEXT_LIMIT } ||
                (record.termId != null && record.termId !in terms)
            ) {
                invalid()
            }
        }
        val files = hashSetOf<String>()
        var bytes = 0L
        for (item in data.evidence) {
            if (!validId(item.id) || !files.add(item.id) || item.recordId !in records ||
                item.name != cleanFileName(item.name, item.kind) || item.size !in 1..ATTACHMENT_LIMIT ||
                !item.sha256.matches(HASH)
            ) {
                invalid()
            }
            bytes += item.size
            if (bytes > TOTAL_LIMIT) throw UserError("附件总量超过本版备份上限（1 GB）。")
        }
        for (items in data.evidence.groupBy(Evidence::recordId).values) {
            if (items.map(Evidence::order).sorted() != items.indices.toList()) invalid()
        }
    }

    private fun entryName(item: Evidence) = "evidence/${item.id}"
    private fun validEntry(name: String) = name.startsWith("evidence/") && validId(name.removePrefix("evidence/"))
    private fun invalid(): Nothing = throw UserError("备份清单或附件校验失败，当前数据未替换。")

    companion object {
        private const val FORMAT = "app.shizheng.backup"
        private const val VERSION = 1
        private const val MANIFEST = "manifest.json"
        private const val MANIFEST_LIMIT = 8L * 1024 * 1024
        private const val TOTAL_LIMIT = 1024L * 1024 * 1024
        private const val LIST_LIMIT = 20_000
        private val HASH = Regex("[0-9a-f]{64}")
    }
}
