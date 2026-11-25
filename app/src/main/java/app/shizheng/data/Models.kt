package app.shizheng.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.util.UUID

enum class Category(val label: String, private val stableId: String? = null) {
    COMPETITION("竞赛"), VOLUNTEER("志愿服务"), PRACTICE("社会工作"), RESEARCH("科研"),
    CULTURE("文体活动"), HONOR("荣誉表彰"), TRAINING("培训"),
    ACADEMIC("学业学情", "b9b92b4d-cfb1-540f-9ec8-762dd92ed41d"), OTHER("其他");

    val id: String get() = stableId ?: name
}

@Serializable
@Entity(tableName = "categories")
data class RecordCategory(
    @PrimaryKey val id: String,
    val name: String,
    val active: Boolean = true
)

val defaultCategories = Category.entries.filterNot { it == Category.TRAINING }.map { RecordCategory(it.id, it.label) }
internal const val OLD_PRACTICE_LABEL = "社会实践"
internal val legacyCategories = listOf(
    RecordCategory("COMPETITION", "竞赛"), RecordCategory("VOLUNTEER", "志愿服务"),
    RecordCategory("PRACTICE", OLD_PRACTICE_LABEL), RecordCategory("RESEARCH", "科研"),
    RecordCategory("CULTURE", "文体活动"), RecordCategory("HONOR", "荣誉表彰"),
    RecordCategory("TRAINING", "培训"), RecordCategory("OTHER", "其他")
)
const val CATEGORY_LIMIT = 40

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

val defaultTerms = listOf("一", "二", "三", "四", "五").flatMap { year ->
    listOf("上", "下").map { half ->
        val name = "大${year}${half}"
        Term(UUID.nameUUIDFromBytes("shizheng-term-$name".encodeToByteArray()).toString(), name, "", "", false)
    }
}

internal fun List<Term>.missingDefaultTerms(): List<Term> =
    defaultTerms.filter { preset -> none { it.name == preset.name || it.id == preset.id } }

internal fun List<Term>.academicYearOptions(): List<Pair<Set<String>, String>> {
    val termsByName = associateBy(Term::name)
    return defaultTerms.chunked(2).map { pair ->
        pair.mapNotNull { termsByName[it.name]?.id }.toSet() to pair.first().name.removeSuffix("上")
    }.filter { it.first.isNotEmpty() }
}

@Serializable
@Entity(
    tableName = "records",
    foreignKeys = [ForeignKey(entity = Term::class, parentColumns = ["id"], childColumns = ["termId"])],
    indices = [Index("termId")]
)
data class Record(
    @PrimaryKey val id: String,
    val title: String,
    val category: String,
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
    @Required val evidence: List<Evidence> = emptyList(),
    val categories: List<RecordCategory> = defaultCategories
) {
    fun termName(id: String?): String = terms.firstOrNull { it.id == id }?.name ?: "未归入学期"
    fun categoryName(id: String?): String? = categories.firstOrNull { it.id == id }?.name
    val currentTermId: String? get() = terms.firstOrNull(Term::current)?.id
    fun filesFor(id: String): List<Evidence> = evidence.filter { it.recordId == id }.sortedBy(Evidence::order)
}

data class RecordDraft(
    val id: String = newId(),
    val title: String = "",
    val category: String? = null,
    val level: Level? = null,
    val start: String = "",
    val end: String = "",
    val ranged: Boolean = false,
    val termId: String? = null,
    val organization: String = "",
    val result: String = "",
    val notes: String = "",
    val files: List<Evidence> = emptyList()
) {
    fun toRecord(terms: List<Term>, categories: List<RecordCategory> = defaultCategories, previousCategory: String? = null): Record {
        if (title.isBlank()) throw UserError("请填写名称。")
        val selectedCategory = category ?: throw UserError("请选择类别。")
        if (categories.none { it.id == selectedCategory && (it.active || it.id == previousCategory) }) {
            throw UserError("所选类别已删除，请重新选择。")
        }
        val hasDate = start.isNotBlank() || (ranged && end.isNotBlank())
        val from = if (hasDate) parseDate(start) ?: throw UserError("请选择有效的开始日期。") else null
        val to = if (hasDate && ranged) parseDate(end) ?: throw UserError("请选择有效的结束日期。") else null
        if (from != null && to != null && to < from) throw UserError("结束日期不能早于开始日期。")
        if (termId != null && terms.none { it.id == termId }) {
            throw UserError("所选学期已不存在，请重新选择。")
        }
        return Record(
            id, title.trim(), selectedCategory, level, from?.toString().orEmpty(), to?.toString(),
            termId, true,
            organization.trim(), result.trim(), notes.trim()
        )
    }

    companion object {
        fun from(record: Record, files: List<Evidence>) = RecordDraft(
            record.id, record.title, record.category, record.level, record.start, record.end.orEmpty(),
            record.end != null, record.termId, record.organization, record.result,
            record.notes, files
        )
    }
}

data class RecordFilter(
    val query: String = "",
    val categories: Set<String> = emptySet(),
    val levels: Set<Level> = emptySet(),
    val termIds: Set<String> = emptySet(),
    val state: ArchiveState = ArchiveState.ALL
) {
    fun apply(snapshot: Snapshot): List<Record> {
        val archivedIds = snapshot.evidence.mapTo(hashSetOf(), Evidence::recordId)
        val keyword = query.trim()
        return snapshot.records.asSequence().filter { record ->
            val archived = record.id in archivedIds
            (categories.isEmpty() || record.category in categories) &&
                (levels.isEmpty() || record.level in levels) &&
                (termIds.isEmpty() || (record.termId ?: NO_TERM) in termIds) &&
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

const val TITLE_LIMIT = 160
const val TEXT_LIMIT = 4_000
const val TERM_LIMIT = 80
const val ATTACHMENT_LIMIT = 100L * 1024 * 1024
const val FILE_NAME_LIMIT = 120
private val uuidPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
fun validId(id: String): Boolean = id.matches(uuidPattern)
fun newId(): String = UUID.randomUUID().toString()

class UserError(message: String) : Exception(message)

interface RecordExporter {
    fun export(data: Snapshot, evidenceFile: (Evidence) -> java.io.File, destination: java.io.OutputStream)
}
