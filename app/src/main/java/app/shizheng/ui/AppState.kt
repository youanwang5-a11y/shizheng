package app.shizheng.ui

import android.annotation.SuppressLint
import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.shizheng.BuildConfig
import app.shizheng.ai.AiClient
import app.shizheng.ai.AiConfig
import app.shizheng.ai.AiConfigStore
import app.shizheng.ai.AiDestination
import app.shizheng.data.Evidence
import app.shizheng.data.EvidenceKind
import app.shizheng.data.PreparedBackup
import app.shizheng.data.Record
import app.shizheng.data.RecordDraft
import app.shizheng.data.RecordFilter
import app.shizheng.data.RecordRepository
import app.shizheng.data.Term
import app.shizheng.data.UserError
import app.shizheng.data.cleanFileName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal enum class Screen(val title: String) {
    RECORDS("拾证"), MINE("我的"), DETAIL("记录详情"), EDITOR("录入记录"),
    EXTRAS("补充信息与证明"), PREVIEW("证明预览"), TERMS("学期管理"), TERM("编辑学期"),
    EXPORT("导出记录"), BACKUP("备份与恢复"), AI_SETTINGS("AI 设置"), CATEGORIES("类别管理"), ABOUT("关于应用")
}

internal class AppState @JvmOverloads constructor(
    app: Application,
    private val aiClient: AiClient = AiClient(builtInKey = BuildConfig.DEEPSEEK_API_KEY)
) : AndroidViewModel(app) {
    val repository = RecordRepository(app)
    val data = repository.data
    private val aiStore = AiConfigStore(app)
    private val welcomePreferences = app.getSharedPreferences("welcome", Application.MODE_PRIVATE)
    var configuration by mutableStateOf(AiConfig.builtInDefault())
        private set
    var configDraft by mutableStateOf(AiConfig())
    var settingsReturn by mutableStateOf(Screen.MINE)
        private set
    var aiText by mutableStateOf("")
    var aiPhoto by mutableStateOf<Evidence?>(null)
        private set
    var testStatus by mutableStateOf("")
        private set
    private var aiJob: Job? = null
    var aiWorking by mutableStateOf(false)
        private set
    var aiSettingsDirty by mutableStateOf(false)
        private set

    var screen by mutableStateOf(Screen.RECORDS)
    var ready by mutableStateOf(false)
        private set
    var showWelcome by mutableStateOf(false)
        private set
    var failedToLoad by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
    var recordId by mutableStateOf<String?>(null)
        private set
    var detailReturn by mutableStateOf(Screen.RECORDS)
        private set
    var filter by mutableStateOf(RecordFilter())
        private set
    var selectedRecordIds by mutableStateOf<Set<String>?>(null)
        private set
    var draft by mutableStateOf<RecordDraft?>(null)
    private var original: RecordDraft? = null
    private var editorReturn = Screen.RECORDS
    var reviewingAi by mutableStateOf(false)
        private set
    val editingExisting get() = editorReturn == Screen.DETAIL
    val unsaved get() = draft != original || reviewingAi || aiText.isNotBlank()
    var shownEvidence by mutableStateOf<Evidence?>(null)
        private set
    private var previewReturn = Screen.DETAIL
    var termDraft by mutableStateOf<Term?>(null)
    var categoriesReturn by mutableStateOf(Screen.MINE)
        private set
    var prepared by mutableStateOf<PreparedBackup?>(null)
        private set
    var captureFile: File? = null
    var exportEvidence: Evidence? = null
        private set
    private var pendingRecordExport: Set<String>? = null

    init { load() }

    fun load() = operation("无法读取本地记录。") {
        failedToLoad = true
        showWelcome = withContext(Dispatchers.IO) {
            if (!welcomePreferences.contains("completed")) persistWelcome(repository.hasLocalDatabase())
            !welcomePreferences.getBoolean("completed", false)
        }
        repository.initialize()
        ready = true
        failedToLoad = false
        try {
            configuration = withContext(Dispatchers.IO) { aiStore.read() }
        } catch (_: Exception) {
            message = "无法读取原 AI 凭据。可以在 AI 设置中重新保存。"
        }
    }

    fun completeWelcome(termId: String?) = operation("无法保存初始设置，请重试。") {
        if (!showWelcome) return@operation
        if (termId != null) {
            val term = data.value.terms.first { it.id == termId }
            repository.saveTerm(term.copy(current = true))
        }
        withContext(Dispatchers.IO) { persistWelcome(completed = true) }
        showWelcome = false
    }

    // The welcome screen closes only after the completion flag is durable.
    @SuppressLint("UseKtx")
    private fun persistWelcome(completed: Boolean) {
        check(welcomePreferences.edit().putBoolean("completed", completed).commit())
    }

    fun changeFilter(next: RecordFilter) {
        if (filter == next) return
        filter = next
        if (selectedRecordIds != null) selectedRecordIds = emptySet()
    }

    fun startSelectingRecords(id: String? = null) {
        selectedRecordIds = if (id == null) emptySet() else setOf(id)
    }

    fun stopSelectingRecords() { selectedRecordIds = null }

    fun toggleRecordSelection(id: String) {
        val selected = selectedRecordIds ?: return
        selectedRecordIds = if (id in selected) selected - id else selected + id
    }

    fun toggleAllRecords(visible: Set<String>) {
        selectedRecordIds = if (selectedRecordIds == visible) emptySet() else visible
    }

    fun openRecord(id: String, returnTo: Screen = Screen.RECORDS) {
        recordId = id
        detailReturn = returnTo
        screen = Screen.DETAIL
    }

    fun editRecord(record: Record? = null, extras: Boolean = false) {
        val initial = record?.let { RecordDraft.from(it, data.value.filesFor(it.id)) }
            ?: RecordDraft(termId = data.value.currentTermId)
        draft = initial
        original = initial
        editorReturn = if (record == null) Screen.RECORDS else Screen.DETAIL
        screen = if (extras) Screen.EXTRAS else Screen.EDITOR
    }

    fun updateDraft(change: (RecordDraft) -> RecordDraft) { draft = draft?.let(change) }

    fun storeDraft() = operation("保存失败，请检查存储空间后重试。") {
        val current = draft ?: return@operation
        val id = repository.save(current)
        val returnTo = if (editingExisting) detailReturn else editorReturn
        clearDraft()
        openRecord(id, returnTo)
        message = "已保存"
    }

    fun discardDraft() = operation("无法清理草稿附件。") {
        draft?.let { repository.discardDraft(it) }
        clearDraft()
        screen = editorReturn
    }

    private fun clearDraft() {
        original = null
        draft = null
        reviewingAi = false
        aiText = ""
        aiPhoto = null
    }

    fun deleteRecord() = operation("删除失败，请重试。") {
        val id = recordId ?: return@operation
        repository.deleteRecord(id)
        recordId = null
        screen = detailReturn
        message = "记录已删除"
    }

    fun deleteSelectedRecords(ids: Set<String>) = operation("删除失败，请重试。") {
        val count = repository.deleteRecords(ids)
        stopSelectingRecords()
        message = "已删除 $count 条记录"
    }

    fun importFiles(uris: List<Uri>, camera: Boolean = false) {
        if (uris.isEmpty()) return
        operation("导入失败，请重新选择文件。") {
            val current = draft ?: return@operation
            val added = mutableListOf<Evidence>()
            val failed = mutableListOf<String>()
            try {
                withContext(Dispatchers.IO) {
                    for (uri in uris) {
                        try {
                            added += repository.evidence.import(current.id, uri, current.files.size + added.size, camera)
                        } catch (error: Exception) {
                            failed += (error as? UserError)?.message ?: "文件不可读或无法写入。"
                        }
                    }
                }
                updateDraft { it.copy(files = it.files + added) }
                message = when {
                    failed.isEmpty() -> "已添加 ${added.size} 个附件"
                    added.isEmpty() -> "导入失败。 ${failed.first()}"
                    else -> "已添加 ${added.size} 个，${failed.size} 个导入失败。 ${failed.first()}"
                }
            } finally {
                if (camera) {
                    captureFile?.delete()
                    captureFile = null
                }
            }
        }
    }

    fun importAiPhoto(uri: Uri) = operation("无法读取所选照片，请重新选择。") {
        val current = draft ?: return@operation
        val store = repository.evidence
        val item = withContext(Dispatchers.IO) {
            store.import(current.id, uri, current.files.size).also {
                if (it.kind != EvidenceKind.PHOTO) {
                    store.remove(it)
                    throw UserError("请选择照片。")
                }
            }
        }
        updateDraft { it.copy(files = it.files + item) }
        aiPhoto = item
    }

    fun selectAiPhoto(item: Evidence?) { aiPhoto = item }

    fun renameFile(item: Evidence, name: String) {
        if (name.isBlank()) {
            message = "请输入附件名称。"
            return
        }
        updateDraft { draft -> draft.copy(files = draft.files.map {
            if (it.id == item.id) it.copy(name = cleanFileName(name, item.kind)) else it
        }) }
    }

    fun moveFile(item: Evidence, offset: Int) = updateDraft { draft ->
        val files = draft.files.toMutableList()
        val from = files.indexOfFirst { it.id == item.id }
        val to = from + offset
        if (from >= 0 && to in files.indices) files.add(to, files.removeAt(from))
        draft.copy(files = files)
    }

    fun deleteFile(item: Evidence) = operation("未能移除该附件。") {
        repository.deleteDraftFile(item)
        updateDraft { it.copy(files = it.files.filterNot { file -> file.id == item.id }) }
        if (aiPhoto?.id == item.id) aiPhoto = null
    }

    fun preview(item: Evidence) {
        shownEvidence = item
        previewReturn = screen
        screen = Screen.PREVIEW
    }

    fun closePreview() {
        shownEvidence = null
        screen = previewReturn
    }

    fun openWord(item: Evidence) {
        try {
            repository.evidence.share(item)
        } catch (error: Exception) {
            message = (error as? UserError)?.message ?: "无法打开这个文件。"
        }
    }

    fun prepareEvidenceExport(item: Evidence, launch: (String, String) -> Unit) =
        operation("无法打开系统文件选择器。") {
            val (name, mime) = withContext(Dispatchers.IO) { repository.evidence.exportNameAndType(item) }
            exportEvidence = item
            try {
                launch(name, mime)
            } catch (error: Exception) {
                exportEvidence = null
                throw error
            }
        }

    fun saveEvidence(uri: Uri?) {
        val item = exportEvidence
        exportEvidence = null
        if (uri == null || item == null) return
        operation("另存失败，无法写入选中的位置。") {
            withContext(Dispatchers.IO) { repository.evidence.copyTo(item, uri) }
            message = "已另存附件"
        }
    }

    fun saveTerm() = operation("保存学期失败。") {
        repository.saveTerm(termDraft ?: return@operation)
        termDraft = null
        screen = Screen.TERMS
        message = "学期已保存"
    }

    fun setCurrent(term: Term) = operation("无法设置当前学期。") {
        repository.saveTerm(term.copy(current = true))
        message = "已设为当前学期"
    }

    fun clearCurrent() = operation("无法清除当前学期。") {
        repository.clearCurrentTerm()
        message = "新记录默认未归入学期"
    }

    fun openCategories() {
        categoriesReturn = screen
        screen = Screen.CATEGORIES
    }

    fun addCategory(name: String, onAdded: () -> Unit) = operation("无法添加类别。") {
        val id = repository.addCategory(name)
        if (categoriesReturn == Screen.EDITOR) updateDraft { it.copy(category = id) }
        onAdded()
        message = "类别已添加"
    }

    fun deleteCategory(id: String) = operation("无法删除类别。") {
        repository.deleteCategory(id)
        if (id in filter.categories) changeFilter(filter.copy(categories = filter.categories - id))
        if (original?.category != id) updateDraft { if (it.category == id) it.copy(category = null) else it }
        message = "类别已删除"
    }

    fun backup(uri: Uri) = operation("备份失败，请检查目标位置及剩余空间。") {
        repository.backupTo(uri)
        message = "备份已保存"
    }

    fun launchRecordExport(ids: Set<String>, launch: () -> Unit) {
        if (ids.isEmpty() || busy || pendingRecordExport != null) return
        pendingRecordExport = ids.toSet()
        try {
            launch()
        } catch (_: Exception) {
            pendingRecordExport = null
            message = "无法打开系统文件选择器。"
        }
    }

    fun saveRecordExport(uri: Uri?) {
        val ids = pendingRecordExport
        pendingRecordExport = null
        if (uri == null) return
        if (ids == null) {
            message = "导出选择已失效，请重新选择记录。"
            return
        }
        operation("导出失败，请检查目标位置、附件及剩余空间。") {
            val count = repository.exportRecords(uri, ids)
            message = "已导出 $count 条记录"
        }
    }

    fun inspect(uri: Uri) = operation("无法读取所选备份。") {
        prepared?.close()
        prepared = null
        prepared = repository.inspectBackup(uri)
    }

    fun closePrepared() = operation("无法清理恢复临时文件。") {
        prepared?.close()
        prepared = null
    }

    fun restore() = operation("恢复过程中断，请重新选择备份并检查剩余空间。") {
        val incoming = prepared ?: return@operation
        prepared = null
        repository.replace(incoming)
        changeFilter(RecordFilter())
        message = "已恢复记录及附件"
    }

    fun openSettings(from: Screen) {
        settingsReturn = from
        configDraft = configuration
        aiSettingsDirty = false
        testStatus = ""
        screen = Screen.AI_SETTINGS
    }

    fun changeSettings(value: AiConfig) {
        configDraft = value
        aiSettingsDirty = true
        testStatus = ""
    }

    fun aiDestination(config: AiConfig): Result<AiDestination> = runCatching { aiClient.destination(config) }

    fun saveSettings() = operation("AI 配置保存失败。") {
        val setting = configDraft.normalized()
        if (setting.enabled) aiClient.destination(setting)
        withContext(Dispatchers.IO) { aiStore.write(setting) }
        configuration = setting
        aiSettingsDirty = false
        screen = settingsReturn
        message = "已保存 AI 设置"
    }

    fun verifySettings() {
        val settings = configDraft
        testStatus = ""
        runAi { aiClient.test(settings); testStatus = "服务已成功响应所选模型。" }
    }

    fun fillDraft() {
        val id = draft?.id ?: return
        val content = aiText
        val photo = aiPhoto
        val settings = configuration
        val categories = data.value.categories
        runAi {
            val image = photo?.let { withContext(Dispatchers.IO) { repository.evidence.photoForRecognition(it) } }
            val fields = withContext(Dispatchers.Default) { aiClient.generate(settings, content, categories, image) }
            if (draft?.id != id) return@runAi
            updateDraft { current ->
                val canFillDate = current.start.isBlank() && current.end.isBlank()
                current.copy(
                    title = current.title.ifBlank { fields.title },
                    category = current.category ?: fields.category,
                    level = current.level ?: fields.level,
                    start = current.start.ifBlank { if (canFillDate) fields.start else "" },
                    end = if (canFillDate) fields.end else current.end,
                    ranged = if (canFillDate && fields.start.isNotBlank()) fields.ranged else current.ranged,
                    organization = current.organization.ifBlank { fields.organization },
                    result = current.result.ifBlank { fields.result },
                    notes = current.notes.ifBlank { fields.notes }
                )
            }
            reviewingAi = true
        }
    }

    fun cancelAi() { aiJob?.cancel() }

    private fun runAi(block: suspend () -> Unit) {
        if (aiWorking || busy) return
        aiWorking = true
        aiJob = viewModelScope.launch {
            try {
                block()
            } catch (error: CancellationException) {
                message = "已取消请求"
                throw error
            } catch (error: Exception) {
                val reason = (error as? UserError)?.message ?: "AI 请求失败，请检查网络与配置。"
                if (screen == Screen.AI_SETTINGS) testStatus = reason
                else message = reason
            } finally {
                aiWorking = false
            }
        }
    }

    private fun operation(fallback: String, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                message = (error as? UserError)?.message ?: fallback
            } finally {
                busy = false
            }
        }
    }
}
