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

class RecordRepository(context: Context) {
    private val app = context.applicationContext
    private val db = Room.databaseBuilder(app, AppDatabase::class.java, "shizheng.db").build()
    private val dao = db.records()
    private val mutex = Mutex()
    private val mutableData = MutableStateFlow(Snapshot())
    val data = mutableData.asStateFlow()
    val evidence = EvidenceStore(app)
    private val backup = BackupArchive(::checkEvidenceFile)
    private val restoreDirectory = File(app.filesDir, "restore-work")

    suspend fun initialize() = locked {
        refresh()
        evidence.clearUnreferenced(data.value.evidence)
        restoreDirectory.deleteRecursively()
        File(app.cacheDir, "camera").listFiles()?.forEach { it.delete() }
    }

    suspend fun save(draft: RecordDraft): String = locked {
        val current = data.value
        val record = draft.toRecord(current.terms)
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
        val start = parseDate(term.start) ?: throw UserError("请选择学期开始日期。")
        val end = parseDate(term.end) ?: throw UserError("请选择学期结束日期。")
        if (term.name.isBlank()) throw UserError("请填写学期名称。")
        if (end < start) throw UserError("学期结束日期不能早于开始日期。")
        db.withTransaction {
            if (term.current) dao.clearCurrent()
            dao.saveTerm(term.copy(name = term.name.trim()))
            val updatedTerms = dao.terms()
            dao.records().filterNot(Record::manualTerm).forEach { record ->
                val from = requireNotNull(parseDate(record.start))
                val to = record.end?.let { requireNotNull(parseDate(it)) } ?: from
                val match = termFor(updatedTerms, from, to)
                if (match != record.termId) dao.saveRecord(record.copy(termId = match))
            }
        }
        refresh()
    }

    suspend fun backupTo(uri: Uri) = locked {
        val temporary = File.createTempFile("backup-", ".zip", app.cacheDir)
        try {
            temporary.outputStream().use { backup.write(data.value, it, evidence::file) }
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
                dao.insertTerms(incoming.terms)
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
        mutableData.value = Snapshot(dao.terms(), dao.records(), dao.evidence())
    }

    private suspend fun <T> locked(action: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock { withContext(NonCancellable) { action() } }
    }
}
