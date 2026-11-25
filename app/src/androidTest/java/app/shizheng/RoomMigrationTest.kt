package app.shizheng

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.shizheng.data.AppDatabase
import app.shizheng.data.Category
import app.shizheng.data.RecordCategory
import app.shizheng.data.RecordsDao
import app.shizheng.data.defaultCategories
import app.shizheng.data.legacyCategories
import app.shizheng.data.newId
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomMigrationTest {
    @Test fun upgradesExportedVersionOneWithoutChangingExistingRelations() = runBlocking {
        val term = newId()
        val record = newId()
        val evidence = newId()
        withMigratedDatabase(1, seed = { db ->
            db.execSQL("INSERT INTO terms VALUES (?, '旧学期', '2026-09-01', '2027-01-31', 1)", arrayOf(term))
            db.execSQL("INSERT INTO records VALUES (?, '旧记录', 'PRACTICE', NULL, '2026-11-24', NULL, ?, 0, '', '', '')",
                arrayOf(record, term))
            db.execSQL("INSERT INTO evidence VALUES (?, ?, '旧证明.pdf', 'PDF', 0, 10, ?)",
                arrayOf(evidence, record, "a".repeat(64)))
        }) { dao ->
            val saved = dao.records().single()
            assertEquals(record, saved.id)
            assertEquals(term, saved.termId)
            assertEquals(Category.PRACTICE.id, saved.category)
            assertEquals("2026-11-24", saved.start)
            assertTrue(dao.terms().single().current)
            assertEquals(evidence, dao.evidence().single().id)
            assertEquals(record, dao.evidence().single().recordId)
            val categories = dao.categories()
            assertEquals(defaultCategories.toSet(), categories.filter(RecordCategory::active).toSet())
            assertEquals("社会工作", categories.single { it.id == saved.category }.name)
            assertFalse(categories.single { it.id == Category.TRAINING.id }.active)
        }
    }

    @Test fun upgradesVersionTwoAndKeepsRetiredAndCustomCategoryAssociations() = runBlocking {
        val record = newId()
        val socialWork = RecordCategory(newId(), "社会工作")
        val academic = RecordCategory(newId(), "学业学情", active = false)
        val oldCategories = legacyCategories.map {
            if (it.id == Category.VOLUNTEER.id) it.copy(active = false) else it
        } + listOf(socialWork, academic)
        withMigratedDatabase(2, seed = { db ->
            for (category in oldCategories) {
                db.execSQL("INSERT INTO categories VALUES (?, ?, ?)",
                    arrayOf<Any>(category.id, category.name, if (category.active) 1 else 0))
            }
            db.execSQL("INSERT INTO records VALUES (?, '旧培训', 'TRAINING', NULL, '', NULL, NULL, 0, '', '', '')",
                arrayOf(record))
        }) { dao ->
            val categories = dao.categories().associateBy(RecordCategory::id)
            assertEquals(socialWork, categories.getValue(socialWork.id))
            assertEquals(academic, categories.getValue(academic.id))
            assertFalse(Category.ACADEMIC.id in categories)
            assertFalse(categories.getValue(Category.VOLUNTEER.id).active)
            assertEquals(RecordCategory(Category.PRACTICE.id, "社会实践", active = false), categories[Category.PRACTICE.id])
            val saved = dao.records().single()
            assertEquals(record, saved.id)
            assertEquals(RecordCategory(Category.TRAINING.id, "培训", active = false), categories[saved.category])
        }
    }

    @Test fun leavesUninitializedVersionTwoCategoriesReadyForDefaultSeeding() = runBlocking {
        withMigratedDatabase(2, seed = {}) { dao ->
            assertTrue(dao.categories().isEmpty())
        }
    }

    private suspend fun withMigratedDatabase(
        version: Int, seed: (SQLiteDatabase) -> Unit, verify: suspend (RecordsDao) -> Unit
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val schema = instrumentation.context.assets.open("app.shizheng.data.AppDatabase/$version.json")
            .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        val name = "migration-${newId()}.db"
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        try {
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val table = entities.getJSONObject(i)
                    val tableName = table.getString("tableName")
                    db.execSQL(table.getString("createSql").replace("\${TABLE_NAME}", tableName))
                    val indices = table.optJSONArray("indices")
                    if (indices != null) for (j in 0 until indices.length()) {
                        db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", tableName))
                    }
                }
                val setup = schema.getJSONArray("setupQueries")
                for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
                seed(db)
                db.version = version
            }
            val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3).build()
            try {
                verify(db.records())
            } finally {
                db.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
