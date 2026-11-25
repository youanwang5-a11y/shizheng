package app.shizheng.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.shizheng.R
import app.shizheng.ai.AI_TEXT_LIMIT
import app.shizheng.ai.AiConfig
import app.shizheng.ai.AiDestination
import app.shizheng.ai.BUILT_IN_MODEL
import app.shizheng.ai.TEST_MESSAGE
import app.shizheng.data.Evidence
import app.shizheng.data.EvidenceKind
import app.shizheng.data.RecordDraft
import app.shizheng.data.Snapshot

@Composable
internal fun AiEntry(state: AppState, data: Snapshot, draft: RecordDraft, selectPhoto: () -> Unit) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    var describing by rememberSaveable { mutableStateOf(false) }
    val settings = state.configuration
    val destination = remember(settings) { state.aiDestination(settings) }
    val target = destination.getOrNull()
    val photo = state.aiPhoto
    val enabled = settings.enabled && target != null && !state.aiWorking
    Surface(
        Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = Color(0xFFFBF7F1),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shadowElevation = 2.dp
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Image(
                    painterResource(R.drawable.illustration_ai), null,
                    Modifier.size(84.dp).clip(MaterialTheme.shapes.medium)
                )
                Column(Modifier.weight(1f)) {
                    Text("AI 填写", style = MaterialTheme.typography.titleLarge)
                    Help("描述经历，添加照片，智能识别并填入关键信息")
                }
            }
            BoxWithConstraints {
                val showIcons = maxWidth >= 314.dp && LocalDensity.current.fontScale <= 1.15f
                val padding = PaddingValues(horizontal = 4.dp, vertical = 5.dp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SecondaryButton({ describing = true }, Modifier.weight(1f), enabled, padding) {
                        if (showIcons) Icon(Icons.Outlined.Edit, null, Modifier.size(16.dp).padding(end = 2.dp))
                        Text("描述经历", textAlign = TextAlign.Center)
                    }
                    SecondaryButton(selectPhoto, Modifier.weight(1f), enabled, padding) {
                        if (showIcons) Icon(painterResource(R.drawable.ic_photo), null, Modifier.size(16.dp).padding(end = 2.dp))
                        Text("添加照片", textAlign = TextAlign.Center)
                    }
                    PrimaryButton({
                        if (state.aiText.isNotBlank() || photo != null) confirm = true
                        else state.message = "请先描述经历或添加照片。"
                    }, Modifier.weight(1.1f), enabled, padding) {
                        if (showIcons) Icon(painterResource(R.drawable.ic_sparkles), null, Modifier.size(16.dp).padding(end = 2.dp))
                        Text("识别并填入", textAlign = TextAlign.Center)
                    }
                }
            }
            if (!settings.enabled || target == null) {
                Help(if (settings.enabled) destination.exceptionOrNull()?.message.orEmpty() else "AI 已关闭。")
                TextButton({ state.openSettings(Screen.EDITOR) }) { Text("打开 AI 设置") }
            } else {
                if (state.aiText.isNotBlank()) {
                    Text(
                        state.aiText, style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                val photos = draft.files.filter { it.kind == EvidenceKind.PHOTO }
                if (photos.isNotEmpty() && !state.aiWorking) {
                    Choice("待识别照片", photo?.name ?: "未选择", listOf(null to "未选择") + photos.map { it to it.name }) {
                        state.selectAiPhoto(it)
                    }
                }
                if (state.aiWorking) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    SecondaryButton(state::cancelAi, Modifier.fillMaxWidth()) { Text("取消识别") }
                }
            }
        }
    }
    if (describing) {
        var description by rememberSaveable { mutableStateOf(state.aiText) }
        AlertDialog(
            onDismissRequest = { describing = false }, title = { Text("描述经历") },
            text = { Field(description, "描述你的经历", AI_TEXT_LIMIT, lines = 3, maxLines = 6) { description = it } },
            confirmButton = { TextButton({ state.aiText = description; describing = false }) { Text("完成") } },
            dismissButton = { TextButton({ describing = false }) { Text("取消") } }
        )
    }
    if (confirm && target != null) AiSendDialog(
        target, state.aiText, "确认发送并填写", photo, data.categories.filter { it.active }.map { it.name },
        { confirm = false }, { confirm = false; state.fillDraft() }
    )
}

@Composable
internal fun AiSettings(state: AppState) {
    val settings = state.configDraft
    var testTarget by remember { mutableStateOf<AiDestination?>(null) }
    Page {
        Panel {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Heading("启用 AI")
                Spacer(Modifier.weight(1f))
                Switch(settings.enabled, { state.changeSettings(settings.copy(enabled = it)) }, enabled = !state.aiWorking)
            }
            Choice("服务", if (settings.useBuiltIn) "内置 DeepSeek" else "自定义接口",
                listOf(true to "内置 DeepSeek", false to "自定义接口")) {
                if (!state.aiWorking) state.changeSettings(settings.copy(useBuiltIn = it))
            }
            if (settings.useBuiltIn) Help("$BUILT_IN_MODEL · 可识别文字和照片")
        }
        if (!settings.useBuiltIn) {
            Help("使用 OpenAI Chat Completions 兼容的 HTTPS 服务。API Key 由本机加密保存。")
            OutlinedTextField(
                settings.address, { if (it.length <= 512) state.changeSettings(settings.copy(address = it)) },
                Modifier.fillMaxWidth(), enabled = !state.aiWorking, singleLine = true,
                label = { Text("接口地址") }, placeholder = { Text("https://api.example.com/v1") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                shape = MaterialTheme.shapes.medium, colors = inputColors()
            )
            OutlinedTextField(
                settings.key, { if (it.length <= 1024) state.changeSettings(settings.copy(key = it)) },
                Modifier.fillMaxWidth(), enabled = !state.aiWorking, singleLine = true,
                label = { Text("API Key") }, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                shape = MaterialTheme.shapes.medium, colors = inputColors()
            )
            OutlinedTextField(
                settings.model, { if (it.length <= 160) state.changeSettings(settings.copy(model = it)) },
                Modifier.fillMaxWidth(), enabled = !state.aiWorking, singleLine = true, label = { Text("模型名称") },
                shape = MaterialTheme.shapes.medium, colors = inputColors()
            )
        }
        if (state.testStatus.isNotBlank()) Panel { Text(state.testStatus) }
        if (state.aiWorking) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            SecondaryButton(state::cancelAi, Modifier.fillMaxWidth()) { Text("取消测试") }
        } else {
            PrimaryButton(state::saveSettings, Modifier.fillMaxWidth()) { Text("保存设置") }
            SecondaryButton({
                state.aiDestination(settings).onSuccess { testTarget = it }.onFailure { state.message = it.message }
            }, Modifier.fillMaxWidth()) { Text("测试连接") }
        }
    }
    testTarget?.let { target ->
        AiSendDialog(target, TEST_MESSAGE, "确认测试", null, null,
            { testTarget = null }, { testTarget = null; state.verifySettings() })
    }
}

@Composable
private fun AiSendDialog(
    target: AiDestination, content: String, action: String, photo: Evidence?, categories: List<String>?,
    dismiss: () -> Unit, send: () -> Unit
) {
    AlertDialog(
        onDismissRequest = dismiss, title = { Text("确认发送内容") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Heading("目标服务")
                Help(target.endpoint.toString())
                Help("模型 · ${target.model}")
                if (content.isNotBlank()) {
                    Heading("本次发送的文字")
                    Text(content)
                }
                if (photo != null) {
                    Heading("本次发送的照片")
                    Text(photo.name)
                    Help("发送压缩后的照片用于识别，原件留在草稿附件中。")
                }
                if (!categories.isNullOrEmpty()) {
                    Heading("本次发送的类别名称")
                    Help(categories.joinToString("、"))
                }
            }
        },
        confirmButton = { TextButton(send) { Text(action) } },
        dismissButton = { TextButton(dismiss) { Text("取消") } }
    )
}
