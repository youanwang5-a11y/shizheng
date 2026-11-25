package app.shizheng.data

import org.junit.Assert.*
import org.junit.Test

class RecordRulesTest {
    private val autumn = Term(newId(), "上学期", "2026-09-01", "2027-01-31", true)
    private val spring = Term(newId(), "下学期", "2027-02-01", "2027-07-31", false)
    private val input = RecordDraft(title = " 图书馆志愿服务 ", category = Category.VOLUNTEER.name, start = "2026-11-18")

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
        assertEquals("", input.copy(start = "", ranged = true).toRecord(emptyList()).start)
        assertThrows(UserError::class.java) { input.copy(start = "", end = "2026-11-28", ranged = true).toRecord(emptyList()) }
    }

    @Test fun storesOnlyTheSelectedTermRegardlessOfDate() {
        val terms = listOf(autumn, spring)
        assertNull(input.toRecord(terms).termId)
        val crossing = input.copy(ranged = true, end = "2027-02-01")
        assertNull(crossing.toRecord(terms).termId)
        assertEquals(spring.id, crossing.copy(termId = spring.id).toRecord(terms).termId)
        assertThrows(UserError::class.java) { input.copy(termId = newId()).toRecord(terms) }
        assertEquals(listOf("大一上", "大一下", "大二上", "大二下"), defaultTerms.take(4).map(Term::name))
    }

    @Test fun keepsDeletedCategoriesOnExistingRecordsOnly() {
        val retired = RecordCategory(newId(), "创新项目", false)
        val draft = input.copy(category = retired.id)
        val choices = defaultCategories + retired
        assertThrows(UserError::class.java) { draft.toRecord(emptyList(), choices) }
        assertEquals(retired.id, draft.toRecord(emptyList(), choices, retired.id).category)
    }

    @Test fun intersectsFiltersAndRecomputesArchiveStateAfterLastFileIsRemoved() {
        val terms = listOf(autumn)
        val record = input.copy(organization = "图书馆", notes = "周三", level = Level.SCHOOL, termId = autumn.id).toRecord(terms)
        val another = input.copy(id = newId(), title = "数学竞赛", start = "2026-11-20", category = Category.COMPETITION.name).toRecord(terms)
        val attachment = Evidence(newId(), record.id, "证明.pdf", EvidenceKind.PDF, 0, 38, "a".repeat(64))
        val archived = Snapshot(terms, listOf(record, another), listOf(attachment))
        val filter = RecordFilter("周三", setOf(Category.VOLUNTEER.name), setOf(Level.SCHOOL), setOf(autumn.id), ArchiveState.ARCHIVED)
        assertEquals(listOf(record), filter.apply(archived))
        assertEquals(listOf(record), filter.copy(query = "图书馆").apply(archived))
        val pending = archived.copy(evidence = emptyList())
        assertTrue(filter.apply(pending).isEmpty())
        assertEquals(listOf(record), filter.copy(state = ArchiveState.PENDING).apply(pending))
        assertEquals(listOf(another, record), RecordFilter().apply(pending))
    }

    @Test fun matchesAnyValueWithinEachGroupAndRequiresAllGroups() {
        val base = input.copy(category = Category.VOLUNTEER.name, termId = autumn.id, level = Level.SCHOOL)
            .toRecord(listOf(autumn, spring))
        val second = base.copy(id = newId(), category = Category.COMPETITION.name, termId = spring.id, level = Level.FACULTY)
        val unassigned = base.copy(id = newId(), termId = null)
        val wrongCategory = base.copy(id = newId(), category = Category.OTHER.name)
        val wrongLevel = base.copy(id = newId(), level = null)
        val data = Snapshot(terms = listOf(autumn, spring), records = listOf(base, second, unassigned, wrongCategory, wrongLevel))
        val filter = RecordFilter(
            categories = setOf(Category.VOLUNTEER.name, Category.COMPETITION.name),
            levels = setOf(Level.SCHOOL, Level.FACULTY),
            termIds = setOf(autumn.id, spring.id)
        )
        assertEquals(setOf(base, second), filter.apply(data).toSet())
        assertEquals(setOf(base, second, unassigned), filter.copy(termIds = filter.termIds + RecordFilter.NO_TERM).apply(data).toSet())
        assertEquals(setOf(base, second, wrongLevel), filter.copy(levels = emptySet()).apply(data).toSet())
    }
}
