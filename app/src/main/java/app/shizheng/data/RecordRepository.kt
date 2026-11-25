package app.shizheng.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream

class RecordRepository(context: Context) {
    private val app = context.applicationContext
    private val databaseName = "shizheng.db"
    private val db = Room.databaseBuilder(app, AppDatabase::class.java, databaseName)
        .addMigrations(AppDatabase.MIGRATION_1_2).build()
    private val dao = db.records()
    private val mutex = Mutex()
    private val mutableData = MutableStateFlow(Snapshot())
    val data = mutableData.asStateFlow()
    val evidence = EvidenceStore(app)
    private val backup = BackupArchive(::checkEvidenceFile)
    private val exporter = ZipRecordExporter { evidence.exportNameAndType(it).first }
    private val restoreDirectory = File(app.filesDir, "restore-work")

    internal fun hasLocalDatabase() = app.getDatabasePath(databaseName).exists()

    suspend fun initialize() = locked {
        db.withTransaction {
            if (dao.categories().isEmpty()) dao.insertCategories(defaultCategories)
            dao.insertTerms(dao.terms().missingDefaultTerms())
        }
        refresh()
        evidence.clearUnreferenced(data.value.evidence)
        restoreDirectory.deleteRecursively()
        File(app.cacheDir, "camera").listFiles()?.forEach { it.delete() }
    }

    suspend fun save(draft: RecordDraft): String = locked {
        val current = data.value
        val record = draft.toRecord(current.terms, current.categories, current.records.firstOrNull { it.id == draft.id }?.category)
        val previous = current.filesFor(record.id)
        val arranged = draft.files.mapIndexed { i, item ->
            if (item.recordId != record.id || !evidence.file(item).isFile) throw UserError("有附件已不可读，请重新导入。")
            item.copy(order = i)
        }
        db.withTransaction {
            dao.saveRecord(record)
            dao.clearEvidence(record.id)
            dao.insertEvidence(arranged)
        }
        val retained = arranged.mapTo(hashSetOf(), Evidence::id)
        previous.filterNot { it.id in retained }.forEach(evidence::remove)
        refresh()
        record.id
    }

    suspend fun discardDraft(draft: RecordDraft) = locked {
        val ids = data.value.filesFor(draft.id).mapTo(hashSetOf(), Evidence::id)
        draft.files.filterNot { it.id in ids }.forEach(evidence::remove)
    }

    suspend fun deleteRecord(id: String) = locked {
        val previous = data.value.filesFor(id)
        dao.deleteRecord(id)
        previous.forEach(evidence::remove)
        refresh()
    }

    suspend fun deleteDraftFile(item: Evidence) = locked {
        if (data.value.evidence.none { it.id == item.id }) evidence.remove(item)
    }

    suspend fun saveTerm(term: Term) = locked {
        val name = term.name.trim()
        if (name.isBlank() || name.length > TERM_LIMIT) throw UserError("请填写有效的学期名称。")
        val terms = data.value.terms
        if (terms.none { it.id == term.id && it.name == name } && terms.any { it.name == name }) {
            throw UserError("该学期已存在。")
        }
        db.withTransaction {
            if (term.current) dao.clearCurrent()
            dao.saveTerm(term.copy(name = name))
        }
        refresh()
    }

    suspend fun clearCurrentTerm() = locked {
        dao.clearCurrent()
        refresh()
    }

    suspend fun addCategory(input: String): String = locked {
        val name = input.trim()
        if (name.isBlank() || name.length > CATEGORY_LIMIT || name.any(Char::isISOControl)) {
            throw UserError("请填写有效的类别名称。")
        }
        if (data.value.categories.any { it.active && it.name.equals(name, ignoreCase = true) }) {
            throw UserError("该类别已存在。")
        }
        val existing = data.value.categories.firstOrNull { !it.active && it.name.equals(name, ignoreCase = true) }
        val category = existing?.copy(active = true) ?: RecordCategory(newId(), name)
        db.withTransaction {
            if (existing != null) dao.activateCategory(category.id) else dao.insertCategories(listOf(category))
        }
        refresh()
        category.id
    }

    suspend fun deleteCategory(id: String) = locked {
        dao.deactivateCategory(id)
        refresh()
    }

    suspend fun backupTo(uri: Uri) = locked {
        writeArchiveTo(uri, "backup-") { backup.write(data.value, it, evidence::file) }
    }

    suspend fun exportRecords(uri: Uri, ids: Set<String>): Int = locked {
        val current = data.value
        val records = current.records.filter { it.id in ids }
        if (records.isEmpty()) throw UserError("请选择要导出的记录。")
        if (records.size != ids.size) throw UserError("所选记录已有变化，请重新选择。")
        val selected = current.copy(records = records, evidence = current.evidence.filter { it.recordId in ids })
        writeArchiveTo(uri, "export-") { exporter.export(selected, evidence::file, it) }
        records.size
    }

    private fun writeArchiveTo(uri: Uri, prefix: String, write: (OutputStream) -> Unit) {
        val temporary = File.createTempFile(prefix, ".zip", app.cacheDir)
        try {
            temporary.outputStream().use(write)
            app.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                temporary.inputStream().use { it.copyTo(output) }
            } ?: throw UserError("无法写入选中的位置。")
        } finally {
            temporary.delete()
        }
    }

    suspend fun inspectBackup(uri: Uri): PreparedBackup = locked {
        app.contentResolver.openInputStream(uri)?.use { backup.prepare(it, restoreDirectory) }
            ?: throw UserError("无法读取所选文件。")
    }

    suspend fun replace(prepared: PreparedBackup) = locked {
        val previous = data.value.evidence
        val transferred = mutableListOf<Evidence>()
        val incoming = prepared.snapshot
        try {
            for (item in incoming.evidence) {
                val replacement = item.copy(id = newId())
                if (!prepared.attachment(item).renameTo(evidence.file(replacement))) {
                    throw IOException("Could not stage attachment")
                }
                transferred += replacement
            }
            db.withTransaction {
                dao.clearRecords()
                dao.clearTerms()
                dao.clearCategories()
                dao.insertTerms(incoming.terms + incoming.terms.missingDefaultTerms())
                dao.insertCategories(incoming.categories)
                dao.insertRecords(incoming.records)
                dao.insertEvidence(transferred)
            }
        } catch (error: Exception) {
            transferred.forEach(evidence::remove)
            throw error
        } finally {
            prepared.close()
        }
        previous.forEach(evidence::remove)
        refresh()
    }

    private suspend fun refresh() {
        val order = defaultTerms.map(Term::name)
        val terms = dao.terms().sortedBy { order.indexOf(it.name).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }
        mutableData.value = Snapshot(terms, dao.records(), dao.evidence(), dao.categories())
    }

    private suspend fun <T> locked(action: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock { withContext(NonCancellable) { action() } }
    }
}
