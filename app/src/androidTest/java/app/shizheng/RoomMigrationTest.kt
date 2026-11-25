package app.shizheng

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.shizheng.data.AppDatabase
import app.shizheng.data.Category
import app.shizheng.data.defaultCategories
import app.shizheng.data.newId
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomMigrationTest {
    @Test fun upgradesExportedVersionOneWithoutChangingExistingRelations() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val schema = instrumentation.context.assets.open("app.shizheng.data.AppDatabase/1.json")
            .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        val name = "migration-${newId()}.db"
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        val term = newId()
        val record = newId()
        val evidence = newId()
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
                db.execSQL("INSERT INTO terms VALUES (?, '旧学期', '2026-09-01', '2027-01-31', 1)", arrayOf(term))
                db.execSQL("INSERT INTO records VALUES (?, '旧记录', 'VOLUNTEER', NULL, '2026-11-24', NULL, ?, 0, '', '', '')",
                    arrayOf(record, term))
                db.execSQL("INSERT INTO evidence VALUES (?, ?, '旧证明.pdf', 'PDF', 0, 10, ?)",
                    arrayOf(evidence, record, "a".repeat(64)))
                db.version = 1
            }
            val db = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(AppDatabase.MIGRATION_1_2).build()
            try {
                val dao = db.records()
                val saved = dao.records().single()
                assertEquals(record, saved.id)
                assertEquals(term, saved.termId)
                assertEquals(Category.VOLUNTEER.name, saved.category)
                assertEquals("2026-11-24", saved.start)
                assertTrue(dao.terms().single().current)
                assertEquals(evidence, dao.evidence().single().id)
                assertEquals(record, dao.evidence().single().recordId)
                assertEquals(defaultCategories.toSet(), dao.categories().toSet())
            } finally {
                db.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
        Unit
    }
}
