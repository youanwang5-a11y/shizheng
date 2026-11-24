package app.shizheng.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert

@Dao
interface RecordsDao {
    @Query("SELECT * FROM terms ORDER BY start DESC, name")
    suspend fun terms(): List<Term>

    @Query("SELECT * FROM records")
    suspend fun records(): List<Record>

    @Query("SELECT * FROM evidence")
    suspend fun evidence(): List<Evidence>

    @Upsert suspend fun saveTerm(term: Term)
    @Upsert suspend fun saveRecord(record: Record)
    @Insert suspend fun insertEvidence(items: List<Evidence>)
    @Insert suspend fun insertTerms(items: List<Term>)
    @Insert suspend fun insertRecords(items: List<Record>)

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
}

@Database(entities = [Term::class, Record::class, Evidence::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun records(): RecordsDao
}
