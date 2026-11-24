package app.shizheng.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.shizheng.data.Snapshot
import app.shizheng.data.TERM_LIMIT
import app.shizheng.data.Term
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
        MenuItem("学期管理", data.terms.firstOrNull(Term::current)?.let { "当前学期 · ${it.name}" } ?: "按日期范围整理经历") {
            state.screen = Screen.TERMS
        }
        MenuItem("备份与恢复", "保存或迁移记录和原始证明") { state.screen = Screen.BACKUP }
        MenuItem("AI 记录", "从经历文字生成可编辑草稿") { state.screen = Screen.AI }
        MenuItem("AI 设置", if (state.configuration.isReady) "已启用 · ${state.configuration.model}" else "开关、接口与模型") {
            state.openSettings(Screen.MINE)
        }
        MenuItem("关于应用", "拾证 · 版本 0.1.0") { state.screen = Screen.ABOUT }
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
        Help("经历的完整日期落在一个学期内时自动归入。也可以为单条记录手动选学期。")
        Button({
            state.termDraft = Term(newId(), "", "", "", data.terms.isEmpty())
            state.screen = Screen.TERM
        }, Modifier.fillMaxWidth()) { Text("新增学期") }
        if (data.terms.isEmpty()) Panel { Heading("还没有学期") }
        for (term in data.terms) Panel {
            Heading(term.name)
            Help("${term.start} 至 ${term.end}")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.Center) {
                OutlinedButton({ state.termDraft = term; state.screen = Screen.TERM }) { Text("编辑学期") }
                if (term.current) Text("当前学期", Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.primary)
                else TextButton({ state.setCurrent(term) }) { Text("设为当前学期") }
            }
        }
    }
}

@Composable
internal fun TermEdit(state: AppState, term: Term) {
    Page {
        Field(term.name, "学期名称", TERM_LIMIT) { state.termDraft = term.copy(name = it) }
        DayField("开始日期", term.start) { state.termDraft = term.copy(start = it) }
        DayField("结束日期", term.end, term.start) { state.termDraft = term.copy(end = it) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("设为当前学期", Modifier.weight(1f))
            Switch(term.current, { state.termDraft = term.copy(current = it) })
        }
        Button(state::saveTerm, Modifier.fillMaxWidth()) { Text("保存学期") }
    }
}

@Composable
internal fun BackupScreen(state: AppState, create: () -> Unit, select: () -> Unit) {
    Page {
        Panel {
            Heading("创建完整备份")
            Help("备份包含记录、学期和证明原件。请选择保存位置并妥善保管，备份可以直接读取。")
            Button(create, Modifier.fillMaxWidth()) { Text("保存备份") }
        }
        Panel {
            Heading("从备份恢复")
            Help("选择拾证备份，验证后可预览数量。确认恢复会替换手机里的记录、学期和证明。")
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
        Help("版本 0.1.0")
        Text("把比赛、活动和志愿服务等经历与对应证明放在一起。")
        Help("记录保存在本机。换手机前请通过「备份与恢复」保存备份。启用 AI 并确认时，输入的文字会发送到你配置的服务。")
    }
}
