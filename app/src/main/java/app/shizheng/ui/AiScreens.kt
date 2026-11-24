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
import app.shizheng.ai.DRAFT_INSTRUCTIONS
import app.shizheng.ai.TEST_MESSAGE
import app.shizheng.data.UserError

@Composable
internal fun AiRecord(state: AppState) {
    var confirm by remember { mutableStateOf(false) }
    if (!state.configuration.isReady) {
        Confirm(
            "需要配置 AI", "启用并填写接口地址、API Key 和模型后，即可从文字生成草稿。你也可以继续手动记录。",
            "去配置", { state.screen = Screen.MINE }, { state.openSettings(Screen.AI) }, "暂不使用"
        )
        return
    }
    Page {
        Help("粘贴经历描述，生成一条可修改的草稿。")
        Field(state.aiText, "经历文字", AI_TEXT_LIMIT, 6) { if (!state.aiWorking) state.aiText = it }
        if (state.aiWorking) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedButton(state::cancelAi, Modifier.fillMaxWidth()) { Text("取消生成") }
        } else Button({ confirm = true }, Modifier.fillMaxWidth(), enabled = state.aiText.isNotBlank()) { Text("生成记录草稿") }
    }
    if (confirm) AiSendDialog(
        state.configuration, state.aiText, DRAFT_INSTRUCTIONS, "确认发送并生成",
        { confirm = false }, { confirm = false; state.generateDraft() }
    )
}

@Composable
internal fun AiSettings(state: AppState) {
    val settings = state.configDraft
    var confirm by remember { mutableStateOf(false) }
    Page {
        Panel {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Heading("启用 AI")
                Spacer(Modifier.weight(1f))
                Switch(settings.enabled, { state.changeSettings(settings.copy(enabled = it)) }, enabled = !state.aiWorking)
            }
            Help("使用支持 OpenAI Chat Completions 的 HTTPS 服务。API Key 由本机加密保存。")
        }
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
        if (state.testStatus.isNotBlank()) Panel { Text(state.testStatus) }
        if (state.aiWorking) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedButton(state::cancelAi, Modifier.fillMaxWidth()) { Text("取消测试") }
        } else {
            Button(state::saveSettings, Modifier.fillMaxWidth()) { Text("保存设置") }
            OutlinedButton({
                try {
                    settings.normalized().requireComplete()
                    confirm = true
                } catch (error: UserError) {
                    state.message = error.message
                }
            }, Modifier.fillMaxWidth()) { Text("测试连接") }
        }
    }
    if (confirm) AiSendDialog(settings, TEST_MESSAGE, null, "确认测试",
        { confirm = false }, { confirm = false; state.verifySettings() })
}

@Composable
private fun AiSendDialog(
    configuration: AiConfig, content: String, instructions: String?, action: String, dismiss: () -> Unit, send: () -> Unit
) {
    val settings = configuration.normalized()
    val endpoint = remember(settings) { settings.requireComplete() }
    AlertDialog(
        onDismissRequest = dismiss, title = { Text("确认发送内容") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Heading("目标服务")
                Help(endpoint.toString())
                Help("模型 · ${settings.model}")
                Heading("本次发送的文字")
                Text(content)
                if (instructions != null) {
                    Heading("随附的整理指令")
                    Help(instructions)
                }
                Help("请求将使用你配置的 API Key 认证。")
            }
        },
        confirmButton = { TextButton(send) { Text(action) } },
        dismissButton = { TextButton(dismiss) { Text("取消") } }
    )
}
