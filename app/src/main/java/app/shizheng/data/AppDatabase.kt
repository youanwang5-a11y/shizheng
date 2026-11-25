package app.shizheng.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Dao
interface RecordsDao {
    @Query("SELECT * FROM terms ORDER BY start DESC, name")
    suspend fun terms(): List<Term>

    @Query("SELECT * FROM records")
    suspend fun records(): List<Record>

    @Query("SELECT * FROM evidence")
    suspend fun evidence(): List<Evidence>

    @Query("SELECT * FROM categories ORDER BY name, id")
    suspend fun categories(): List<RecordCategory>

    @Upsert suspend fun saveTerm(term: Term)
    @Upsert suspend fun saveRecord(record: Record)
    @Insert suspend fun insertEvidence(items: List<Evidence>)
    @Insert suspend fun insertTerms(items: List<Term>)
    @Insert suspend fun insertRecords(items: List<Record>)
    @Insert suspend fun insertCategories(items: List<RecordCategory>)

    @Query("UPDATE categories SET active = 0 WHERE id = :id")
    suspend fun deactivateCategory(id: String)

    @Query("UPDATE categories SET active = 1 WHERE id = :id")
    suspend fun activateCategory(id: String)

    @Query("UPDATE terms SET current = 0 WHERE current = 1")
    suspend fun clearCurrent()

    @Query("DELETE FROM evidence WHERE recordId = :recordId")
    suspend fun clearEvidence(recordId: String)

    @Query("DELETE FROM records WHERE id = :id")
    suspend fun deleteRecord(id: String)

    @Query("DELETE FROM records")
    suspend fun clearRecords()

    @Query("DELETE FROM terms")
    suspend fun clearTerms()

    @Query("DELETE FROM categories")
    suspend fun clearCategories()
}

@Database(entities = [Term::class, Record::class, Evidence::class, RecordCategory::class], version = 2, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun records(): RecordsDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE categories (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, active INTEGER NOT NULL)")
                defaultCategories.forEach { category ->
                    db.execSQL("INSERT INTO categories (id, name, active) VALUES (?, ?, 1)", arrayOf(category.id, category.name))
                }
            }
        }
    }
}
