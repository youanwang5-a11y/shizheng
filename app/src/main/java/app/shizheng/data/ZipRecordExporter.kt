package app.shizheng.data

import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal class ZipRecordExporter(private val exportName: (Evidence) -> String) : RecordExporter {
    override fun export(data: Snapshot, evidenceFile: (Evidence) -> File, destination: OutputStream) {
        val records = RecordFilter().apply(data)
        val attachments = data.evidence.groupBy(Evidence::recordId)
        val categories = data.categories.associate { it.id to it.name }
        val terms = data.terms.associate { it.id to it.name }
        ZipOutputStream(destination.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("记录清单.csv"))
            zip.write("\uFEFF".encodeToByteArray())
            zip.writeRow(listOf("序号", "名称", "类别", "级别", "开始日期", "结束日期", "学期",
                "颁发／组织单位", "结果", "备注", "状态", "附件数", "附件目录"))
            records.forEachIndexed { index, record ->
                val files = attachments[record.id].orEmpty()
                zip.writeRow(listOf(
                    (index + 1).toString(), record.title, categories[record.category].orEmpty(),
                    record.level?.label.orEmpty(), record.start, record.end.orEmpty(),
                    terms[record.termId] ?: "未归入学期", record.organization, record.result, record.notes,
                    if (files.isEmpty()) "待补证明" else "已归档", files.size.toString(),
                    if (files.isEmpty()) "" else attachmentDirectory(index)
                ))
            }
            zip.closeEntry()
            records.forEachIndexed { index, record ->
                attachments[record.id].orEmpty().sortedBy(Evidence::order).forEachIndexed { fileIndex, item ->
                    val name = portableName(exportName(item))
                    zip.putNextEntry(ZipEntry("${attachmentDirectory(index)}/${fileIndex + 1}-$name"))
                    val digest = evidenceFile(item).inputStream().use { copyAndHash(it, zip, item.size) }
                    if (digest != ContentDigest(item.size, item.sha256)) {
                        throw UserError("有附件校验失败，请检查原件后再导出。")
                    }
                    zip.closeEntry()
                }
            }
        }
    }

    private fun attachmentDirectory(index: Int) = "证明附件/${index + 1}"

    private fun portableName(name: String): String {
        val safe = name.replace(Regex("""[<>:"/\\|?*\p{Cc}]"""), "_").trim().trimEnd('.')
        val extension = safe.substringAfterLast('.', "").takeIf { it.matches(Regex("[A-Za-z0-9]{1,12}")) }
        val stem = if (extension == null) safe else safe.substringBeforeLast('.')
        val shortStem = stem.substring(0, stem.offsetByCodePoints(0, minOf(50, stem.codePointCount(0, stem.length))))
            .trimEnd(' ', '.').ifEmpty { "证明" }
        return if (extension == null) shortStem else "$shortStem.$extension"
    }

    private fun ZipOutputStream.writeRow(columns: List<String>) {
        val row = columns.joinToString(",") { value ->
            // Keep imported text inert when a spreadsheet opens the CSV.
            val formula = value.trimStart().firstOrNull()?.let { it in "=+-@＝＋－＠" } == true
            val escaped = value.replace("\"", "\"\"")
            "\"${if (formula) "'" else ""}$escaped\""
        }
        write("$row\r\n".encodeToByteArray())
    }
}
