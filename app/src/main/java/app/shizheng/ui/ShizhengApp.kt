package app.shizheng.ui

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.shizheng.data.Evidence
import app.shizheng.data.EvidenceKind
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShizhengApp(state: AppState) {
    val data by state.data.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val screen = state.screen
    val atRoot = screen == Screen.RECORDS || screen == Screen.MINE
    var leaveEditor by remember { mutableStateOf(false) }
    var leaveSettings by remember { mutableStateOf(false) }
    val message = state.message
    LaunchedEffect(message) {
        if (message != null) {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(message, duration = SnackbarDuration.Short)
            if (state.message == message) state.message = null
        }
    }
    val back: () -> Unit = {
        if (!state.busy) {
            if (state.aiWorking) state.cancelAi()
            else when (screen) {
                Screen.DETAIL -> state.screen = state.detailReturn
                Screen.PREVIEW -> state.closePreview()
                Screen.EXTRAS -> state.screen = Screen.EDITOR
                Screen.EDITOR -> if (state.unsaved) leaveEditor = true else state.discardDraft()
                Screen.TERM -> { state.termDraft = null; state.screen = Screen.TERMS }
                Screen.CATEGORIES -> state.screen = state.categoriesReturn
                Screen.AI_SETTINGS -> if (state.aiSettingsDirty) leaveSettings = true else state.screen = state.settingsReturn
                Screen.TERMS, Screen.BACKUP, Screen.ABOUT -> state.screen = Screen.MINE
                else -> Unit
            }
        }
    }
    BackHandler(enabled = !atRoot || state.busy, onBack = back)

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        state.captureFile?.let { file ->
            if (ok && state.draft != null) state.importFiles(listOf(state.repository.evidence.uri(file)), camera = true)
            else {
                file.delete()
                state.captureFile = null
            }
        }
    }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { state.importFiles(it) }
    val aiPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let(state::importAiPhoto) }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { state.importFiles(it) }
    val actions = EvidenceActions(
        camera = {
            try {
                val file = state.repository.evidence.cameraFile()
                state.captureFile = file
                camera.launch(state.repository.evidence.uri(file))
            } catch (_: Exception) {
                state.captureFile?.delete()
                state.captureFile = null
                state.message = "无法打开相机，可以从相册或文件导入。"
            }
        },
        photos = {
            launchPicker(state) { photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        },
        files = {
            launchPicker(state) {
                files.launch(arrayOf("image/*", EvidenceKind.PDF.mime, EvidenceKind.DOC.mime, EvidenceKind.DOCX.mime))
            }
        }
    )
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        state.saveEvidence(result.data?.data?.takeIf { result.resultCode == Activity.RESULT_OK })
    }
    val exportFile: (Evidence) -> Unit = { item ->
        state.prepareEvidenceExport(item) { name, mime ->
            saveFile.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(mime).putExtra(Intent.EXTRA_TITLE, name))
        }
    }
    val createBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) {
        it?.let(state::backup)
    }
    val selectBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(state::inspect) }
    val backup: () -> Unit = { launchPicker(state) { createBackup.launch("拾证-${LocalDate.now()}.shizheng.zip") } }

    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (screen == Screen.EDITOR) when {
                            state.editingExisting -> "编辑记录"
                            else -> "新建记录"
                        } else screen.title, fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    if (!atRoot) IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = {
            if (atRoot) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(
                    screen == Screen.RECORDS, { state.screen = Screen.RECORDS },
                    icon = { Icon(Icons.AutoMirrored.Filled.List, null) }, label = { Text("记录") }
                )
                NavigationBarItem(
                    screen == Screen.MINE, { state.screen = Screen.MINE },
                    icon = { Icon(Icons.Default.Person, null) }, label = { Text("我的") }
                )
            }
        },
        floatingActionButton = {
            if (screen == Screen.RECORDS && state.ready) ExtendedFloatingActionButton(
                text = { Text("新增") }, icon = { Icon(Icons.Default.Add, null) }, onClick = { state.editRecord() },
                modifier = Modifier.semantics { contentDescription = "新增记录" },
                containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (!state.ready) {
                if (state.failedToLoad && !state.busy) Page {
                    Help("无法读取本地记录。")
                    Button(state::load) { Text("重试") }
                }
            } else when (screen) {
                Screen.RECORDS -> RecordList(state, data)
                Screen.MINE -> Mine(state, data)
                Screen.DETAIL -> data.records.firstOrNull { it.id == state.recordId }?.let { RecordDetail(state, data, it) }
                Screen.EDITOR, Screen.EXTRAS -> state.draft?.let {
                    EditRecord(state, data, it, screen == Screen.EXTRAS, actions) {
                        launchPicker(state) { aiPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                    }
                }
                Screen.PREVIEW -> state.shownEvidence?.let { EvidencePreview(state, it, exportFile) }
                Screen.TERMS -> Terms(state, data)
                Screen.TERM -> state.termDraft?.let { TermEdit(state, it) }
                Screen.CATEGORIES -> Categories(state, data)
                Screen.BACKUP -> BackupScreen(state, backup) {
                    launchPicker(state) { selectBackup.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }
                }
                Screen.AI_SETTINGS -> AiSettings(state)
                Screen.ABOUT -> About()
            }
        }
    }
    if (leaveEditor) Confirm(
        "放弃未保存修改？", "本次填写和附件改动将丢失。",
        "放弃修改", { leaveEditor = false }, { leaveEditor = false; state.discardDraft() }, "继续编辑"
    )
    if (leaveSettings) Confirm(
        "放弃 AI 设置修改？", "本次配置尚未保存。", "放弃修改", { leaveSettings = false },
        { leaveSettings = false; state.screen = state.settingsReturn }, "继续编辑"
    )
    if (state.busy) Dialog(onDismissRequest = {}) {
        Surface(shape = MaterialTheme.shapes.large) {
            Row(Modifier.padding(22.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                Text(if (state.ready) "正在处理…" else "正在读取本地记录…")
            }
        }
    }
}

private fun launchPicker(state: AppState, launch: () -> Unit) {
    try {
        launch()
    } catch (_: Exception) {
        state.message = "无法打开系统文件选择器。"
    }
}
