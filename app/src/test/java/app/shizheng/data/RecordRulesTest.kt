package app.shizheng.data

import org.junit.Assert.*
import org.junit.Test

class RecordRulesTest {
    private val autumn = Term(newId(), "上学期", "2026-09-01", "2027-01-31", true)
    private val spring = Term(newId(), "下学期", "2027-02-01", "2027-07-31", false)
    private val input = RecordDraft(title = " 图书馆志愿服务 ", category = Category.VOLUNTEER, start = "2026-11-18")

    @Test fun validatesRequiredFieldsAndCalendarDates() {
        assertThrows(UserError::class.java) { input.copy(title = "  ").toRecord(emptyList()) }
        assertThrows(UserError::class.java) { input.copy(category = null).toRecord(emptyList()) }
        assertThrows(UserError::class.java) { input.copy(start = "2026-02-29").toRecord(emptyList()) }
        assertThrows(UserError::class.java) { input.copy(ranged = true, end = "2026-11-17").toRecord(emptyList()) }
        assertThrows(UserError::class.java) { input.copy(ranged = true).toRecord(emptyList()) }
        assertNotNull(parseDate("2028-02-29"))
        val saved = input.toRecord(emptyList())
        assertEquals("图书馆志愿服务", saved.title)
        assertNull(saved.end)
    }

    @Test fun assignsOnlyUniqueContainingTermWithManualOverride() {
        val terms = listOf(autumn, spring)
        assertEquals(autumn.id, input.toRecord(terms).termId)
        val crossing = input.copy(ranged = true, end = "2027-02-01")
        assertNull(crossing.toRecord(terms).termId)
        assertEquals(spring.id, crossing.copy(manualTerm = true, termId = spring.id).toRecord(terms).termId)
        assertNull(input.toRecord(terms + autumn.copy(id = newId())).termId)
        assertNull(input.copy(manualTerm = true).toRecord(terms).termId)
        assertThrows(UserError::class.java) { input.copy(manualTerm = true, termId = newId()).toRecord(terms) }
    }

    @Test fun intersectsFiltersAndRecomputesArchiveStateAfterLastFileIsRemoved() {
        val terms = listOf(autumn)
        val record = input.copy(organization = "图书馆", notes = "周三", level = Level.SCHOOL).toRecord(terms)
        val another = input.copy(id = newId(), title = "数学竞赛", start = "2026-11-20", category = Category.COMPETITION).toRecord(terms)
        val attachment = Evidence(newId(), record.id, "证明.pdf", EvidenceKind.PDF, 0, 38, "a".repeat(64))
        val archived = Snapshot(terms, listOf(record, another), listOf(attachment))
        val filter = RecordFilter("周三", Category.VOLUNTEER, Level.SCHOOL, autumn.id, ArchiveState.ARCHIVED)
        assertEquals(listOf(record), filter.apply(archived))
        assertEquals(listOf(record), filter.copy(query = "图书馆").apply(archived))
        val pending = archived.copy(evidence = emptyList())
        assertTrue(filter.apply(pending).isEmpty())
        assertEquals(listOf(record), filter.copy(state = ArchiveState.PENDING).apply(pending))
        assertEquals(listOf(another, record), RecordFilter().apply(pending))
    }
}
