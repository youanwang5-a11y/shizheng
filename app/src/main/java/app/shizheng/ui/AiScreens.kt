package app.shizheng.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
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
    var confirm by remember { mutableStateOf(false) }
    val settings = state.configuration
    val destination = state.aiDestination(settings)
    val target = destination.getOrNull()
    val photo = state.aiPhoto
    Panel {
        Heading("AI 智能填写")
        if (!settings.enabled || target == null) {
            Help(if (settings.enabled) destination.exceptionOrNull()?.message.orEmpty() else "AI 已关闭。")
            TextButton({ state.openSettings(Screen.EDITOR) }) { Text("打开 AI 设置") }
        } else {
            Field(state.aiText, "描述你的经历", AI_TEXT_LIMIT, lines = 2, maxLines = 5) { if (!state.aiWorking) state.aiText = it }
            val photos = draft.files.filter { it.kind == EvidenceKind.PHOTO }
            if (photos.isNotEmpty() && !state.aiWorking) {
                Choice("待识别照片", photo?.name ?: "未选择",
                    listOf(null to "未选择") + photos.map { it to it.name }, state::selectAiPhoto)
            }
            if (state.aiWorking) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                OutlinedButton(state::cancelAi, Modifier.fillMaxWidth()) { Text("取消识别") }
            } else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(selectPhoto) { Text("选图识别") }
                Button({ confirm = true }, enabled = state.aiText.isNotBlank() || photo != null) { Text("识别并填入") }
            }
        }
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
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )
            OutlinedTextField(
                settings.key, { if (it.length <= 1024) state.changeSettings(settings.copy(key = it)) },
                Modifier.fillMaxWidth(), enabled = !state.aiWorking, singleLine = true,
                label = { Text("API Key") }, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
            )
            OutlinedTextField(
                settings.model, { if (it.length <= 160) state.changeSettings(settings.copy(model = it)) },
                Modifier.fillMaxWidth(), enabled = !state.aiWorking, singleLine = true, label = { Text("模型名称") }
            )
        }
        if (state.testStatus.isNotBlank()) Panel { Text(state.testStatus) }
        if (state.aiWorking) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedButton(state::cancelAi, Modifier.fillMaxWidth()) { Text("取消测试") }
        } else {
            Button(state::saveSettings, Modifier.fillMaxWidth()) { Text("保存设置") }
            OutlinedButton({
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
