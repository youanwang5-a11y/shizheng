package app.shizheng.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.util.UUID

enum class Category(val label: String) {
    COMPETITION("竞赛"), VOLUNTEER("志愿服务"), PRACTICE("社会实践"), RESEARCH("科研"),
    CULTURE("文体活动"), HONOR("荣誉表彰"), TRAINING("培训"), OTHER("其他")
}

enum class Level(val label: String) {
    NATIONAL("国家级"), PROVINCE("省级"), CITY("市级"), SCHOOL("校级"), FACULTY("院级"), OTHER("其他")
}

enum class ArchiveState(val label: String) {
    ALL("全部"), PENDING("待补证明"), ARCHIVED("已归档")
}

enum class EvidenceKind(val label: String, val mime: String, val extension: String) {
    PHOTO("照片", "image/*", "img"),
    PDF("PDF", "application/pdf", "pdf"),
    DOC("Word", "application/msword", "doc"),
    DOCX("Word", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx")
}

@Serializable
@Entity(tableName = "terms")
data class Term(
    @PrimaryKey val id: String,
    val name: String,
    val start: String,
    val end: String,
    val current: Boolean
)

@Serializable
@Entity(
    tableName = "records",
    foreignKeys = [ForeignKey(entity = Term::class, parentColumns = ["id"], childColumns = ["termId"])],
    indices = [Index("termId")]
)
data class Record(
    @PrimaryKey val id: String,
    val title: String,
    val category: Category,
    val level: Level?,
    val start: String,
    val end: String?,
    val termId: String?,
    val manualTerm: Boolean,
    val organization: String,
    val result: String,
    val notes: String
)

@Serializable
@Entity(
    tableName = "evidence",
    foreignKeys = [
        ForeignKey(
            entity = Record::class, parentColumns = ["id"], childColumns = ["recordId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("recordId")]
)
data class Evidence(
    @PrimaryKey val id: String,
    val recordId: String,
    val name: String,
    val kind: EvidenceKind,
    val order: Int,
    val size: Long,
    val sha256: String
)

@Serializable
data class Snapshot(
    @Required val terms: List<Term> = emptyList(),
    @Required val records: List<Record> = emptyList(),
    @Required val evidence: List<Evidence> = emptyList()
) {
    fun termName(id: String?): String = terms.firstOrNull { it.id == id }?.name ?: "未归入学期"
    fun filesFor(id: String): List<Evidence> = evidence.filter { it.recordId == id }.sortedBy(Evidence::order)
}

data class RecordDraft(
    val id: String = newId(),
    val title: String = "",
    val category: Category? = null,
    val level: Level? = null,
    val start: String = "",
    val end: String = "",
    val ranged: Boolean = false,
    val termId: String? = null,
    val manualTerm: Boolean = false,
    val organization: String = "",
    val result: String = "",
    val notes: String = "",
    val files: List<Evidence> = emptyList()
) {
    fun toRecord(terms: List<Term>): Record {
        if (title.isBlank()) throw UserError("请填写名称。")
        val selectedCategory = category ?: throw UserError("请选择类别。")
        val from = parseDate(start) ?: throw UserError("请选择有效的开始日期。")
        val to = if (ranged) parseDate(end) ?: throw UserError("请选择有效的结束日期。") else from
        if (to < from) throw UserError("结束日期不能早于开始日期。")
        if (manualTerm && termId != null && terms.none { it.id == termId }) {
            throw UserError("所选学期已不存在，请重新选择。")
        }
        return Record(
            id, title.trim(), selectedCategory, level, from.toString(), to.toString().takeIf { ranged },
            if (manualTerm) termId else termFor(terms, from, to), manualTerm,
            organization.trim(), result.trim(), notes.trim()
        )
    }

    fun shownTerm(terms: List<Term>): String? {
        if (manualTerm) return termId
        val from = parseDate(start) ?: return null
        val to = if (ranged) parseDate(end) ?: return null else from
        return termFor(terms, from, to)
    }

    companion object {
        fun from(record: Record, files: List<Evidence>) = RecordDraft(
            record.id, record.title, record.category, record.level, record.start, record.end.orEmpty(),
            record.end != null, record.termId, record.manualTerm, record.organization, record.result,
            record.notes, files
        )
    }
}

data class RecordFilter(
    val query: String = "",
    val category: Category? = null,
    val level: Level? = null,
    val termId: String? = null,
    val state: ArchiveState = ArchiveState.ALL
) {
    fun apply(snapshot: Snapshot): List<Record> {
        val archivedIds = snapshot.evidence.mapTo(hashSetOf(), Evidence::recordId)
        val keyword = query.trim()
        return snapshot.records.asSequence().filter { record ->
            val archived = record.id in archivedIds
            (category == null || record.category == category) &&
                (level == null || record.level == level) &&
                (termId == null || (record.termId ?: NO_TERM) == termId) &&
                (state == ArchiveState.ALL || archived == (state == ArchiveState.ARCHIVED)) &&
                listOf(record.title, record.organization, record.notes).any { it.contains(keyword, ignoreCase = true) }
        }.sortedWith(compareByDescending<Record> { it.start }.thenByDescending { it.end ?: it.start }.thenBy { it.title })
            .toList()
    }

    companion object { const val NO_TERM = "__unassigned__" }
}

fun parseDate(value: String): LocalDate? {
    if (!value.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}"))) return null
    return runCatching { LocalDate.parse(value) }.getOrNull()?.takeIf { it.year in 1900..2200 }
}

fun termFor(terms: List<Term>, from: LocalDate, to: LocalDate): String? =
    if (to < from) null else terms.singleOrNull { it.start <= from.toString() && it.end >= to.toString() }?.id

const val TITLE_LIMIT = 160
const val TEXT_LIMIT = 4_000
const val TERM_LIMIT = 80
const val ATTACHMENT_LIMIT = 100L * 1024 * 1024
const val FILE_NAME_LIMIT = 120
private val uuidPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
fun validId(id: String): Boolean = id.matches(uuidPattern)
fun newId(): String = UUID.randomUUID().toString()

class UserError(message: String) : Exception(message)

// Implementations can project the same snapshot and open original evidence when an export format is chosen.
interface RecordExporter {
    fun export(data: Snapshot, evidenceFile: (Evidence) -> java.io.File, destination: java.io.OutputStream)
}
