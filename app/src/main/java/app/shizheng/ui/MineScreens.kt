package app.shizheng.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.shizheng.BuildConfig
import app.shizheng.data.Snapshot
import app.shizheng.data.CATEGORY_LIMIT
import app.shizheng.data.RecordCategory
import app.shizheng.data.TERM_LIMIT
import app.shizheng.data.Term
import app.shizheng.data.defaultTerms
import app.shizheng.data.newId

@Composable
internal fun Mine(state: AppState, data: Snapshot) {
    val archived = data.evidence.map { it.recordId }.distinct().size
    Page {
        Panel {
            Heading("数据概况")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Count("全部记录", data.records.size)
                Count("待补证明", data.records.size - archived)
                Count("已归档", archived)
            }
        }
        MenuItem("学期管理", data.currentTermId?.let { "当前学期 · ${data.termName(it)}" } ?: "设置新增记录的默认学期") {
            state.screen = Screen.TERMS
        }
        MenuItem("类别管理", "添加或删除记录类别", state::openCategories)
        MenuItem("备份与恢复", "保存或迁移记录和原始证明") { state.screen = Screen.BACKUP }
        MenuItem("AI 记录", "用经历描述或照片填写新记录") { state.editRecord(from = Screen.MINE) }
        val ai = state.aiDestination(state.configuration).getOrNull()
        MenuItem("AI 设置", if (state.configuration.enabled && ai != null) "已启用 · ${ai.model}" else "开关、接口与模型") {
            state.openSettings(Screen.MINE)
        }
        MenuItem("关于应用", "拾证 · 版本 ${BuildConfig.VERSION_NAME}") { state.screen = Screen.ABOUT }
    }
}

@Composable
private fun Count(title: String, value: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value.toString(), style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
internal fun Terms(state: AppState, data: Snapshot) {
    Page {
        Panel {
            Choice("当前学期", data.termName(data.currentTermId),
                listOf(null to "未归入学期") + data.terms.map { it.id to it.name }) { id ->
                val term = data.terms.firstOrNull { it.id == id }
                if (term == null) state.clearCurrent() else state.setCurrent(term)
            }
            Help("新增记录默认选择当前学期。每条记录都可以单独修改。")
        }
        Button({
            state.termDraft = Term(newId(), "", "", "", false)
            state.screen = Screen.TERM
        }, Modifier.fillMaxWidth()) { Text("新增自定义学期") }
        for (term in data.terms.filter { term -> defaultTerms.none { it.name == term.name } }) {
            MenuItem(term.name, "编辑自定义学期") { state.termDraft = term; state.screen = Screen.TERM }
        }
    }
}

@Composable
internal fun TermEdit(state: AppState, term: Term) {
    Page {
        Field(term.name, "学期名称", TERM_LIMIT) { state.termDraft = term.copy(name = it) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("设为当前学期", Modifier.weight(1f))
            Switch(term.current, { state.termDraft = term.copy(current = it) })
        }
        Button(state::saveTerm, Modifier.fillMaxWidth()) { Text("保存学期") }
    }
}

@Composable
internal fun Categories(state: AppState, data: Snapshot) {
    var name by remember { mutableStateOf("") }
    var remove by remember { mutableStateOf<RecordCategory?>(null) }
    Page {
        Panel {
            Field(name, "新类别名称", CATEGORY_LIMIT) { name = it }
            Button({ state.addCategory(name) { name = "" } }, Modifier.fillMaxWidth(), enabled = name.isNotBlank()) {
                Text("添加类别")
            }
        }
        val active = data.categories.filter(RecordCategory::active)
        if (active.isEmpty()) Help("添加类别后即可用于新记录。")
        for (category in active) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(category.name, Modifier.weight(1f))
                TextButton({ remove = category }) { Text("删除") }
            }
        }
    }
    remove?.let { category ->
        Confirm(
            "删除「${category.name}」？", "已有记录会保留该类别，新记录将无法选择它。", "删除类别",
            { remove = null }, { remove = null; state.deleteCategory(category.id) }
        )
    }
}

@Composable
internal fun BackupScreen(state: AppState, create: () -> Unit, select: () -> Unit) {
    Page {
        Panel {
            Heading("创建完整备份")
            Help("备份包含记录、学期、类别和证明原件。请选择保存位置并妥善保管，备份可以直接读取。")
            Button(create, Modifier.fillMaxWidth()) { Text("保存备份") }
        }
        Panel {
            Heading("从备份恢复")
            Help("选择拾证备份，验证后可预览数量。确认恢复会替换手机里的记录、学期、类别和证明。")
            OutlinedButton(select, Modifier.fillMaxWidth()) { Text("选择备份文件") }
        }
    }
    val incoming = state.prepared?.snapshot
    if (incoming != null) AlertDialog(
        onDismissRequest = { if (!state.busy) state.closePrepared() },
        title = { Text("替换本机数据？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Help("已通过校验 · ${incoming.records.size} 条记录、${incoming.terms.size} 个学期、${incoming.evidence.size} 个附件。")
                Text("确认后将替换本机的记录、学期和证明。可以先保存一份本机备份。")
                OutlinedButton(create, Modifier.fillMaxWidth(), enabled = !state.busy) { Text("先备份当前数据") }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.message == "备份已保存") Help("当前数据的备份已保存。")
            }
        },
        confirmButton = { TextButton(state::restore, enabled = !state.busy) { Text("确认替换") } },
        dismissButton = { TextButton(state::closePrepared, enabled = !state.busy) { Text("取消") } }
    )
}

@Composable
internal fun About() = Page {
    Panel {
        Heading("拾证")
        Help("版本 ${BuildConfig.VERSION_NAME}")
        Text("把比赛、活动和志愿服务等经历与对应证明放在一起。")
        Help("记录保存在本机。换手机前请通过「备份与恢复」保存备份。启用 AI 并确认时，输入的文字会发送到你配置的服务。")
    }
}
