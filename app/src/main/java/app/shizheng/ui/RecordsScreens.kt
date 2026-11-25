package app.shizheng.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.shizheng.data.*

@Composable
internal fun RecordList(state: AppState, data: Snapshot) {
    var showFilters by remember { mutableStateOf(false) }
    val filter = state.filter
    val results = remember(data, filter) { filter.apply(data) }
    val filesByRecord = remember(data) { data.evidence.groupBy(Evidence::recordId) }

    LazyColumn(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 102.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    filter.query, { state.filter = filter.copy(query = it.take(200)) },
                    Modifier.fillMaxWidth(), singleLine = true, shape = MaterialTheme.shapes.large,
                    placeholder = { Text("搜索名称、单位或备注") },
                    leadingIcon = { Icon(Icons.Default.Search, "搜索记录") },
                    trailingIcon = {
                        if (filter.query.isNotEmpty()) TextButton({ state.filter = filter.copy(query = "") }) { Text("清空") }
                    }
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (status in ArchiveState.entries) {
                            FilterChip(filter.state == status, { state.filter = filter.copy(state = status) }, { Text(status.label) })
                        }
                    }
                    TextButton({ showFilters = true }) { Text("筛选") }
                }
                val conditions = listOfNotNull(data.categoryName(filter.category), filter.level?.label, filter.termId?.let(data::termName))
                if (conditions.isNotEmpty()) Help(conditions.joinToString(" · "))
                if (results.isNotEmpty()) Help("${results.size} 条记录")
            }
        }
        if (results.isEmpty()) {
            item {
                Panel {
                    if (data.records.isEmpty()) {
                        Heading("还没有记录")
                        Help("添加一次经历，证明可以稍后补充。")
                    } else {
                        Heading("没有符合条件的记录")
                        Help("试试其他关键词，或清除筛选。")
                        TextButton({ state.filter = RecordFilter() }) { Text("清除搜索和筛选") }
                    }
                }
            }
        }
        items(results, key = Record::id) { record ->
            val files = filesByRecord[record.id].orEmpty()
            Surface(
                onClick = { state.openRecord(record.id) },
                modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(Modifier.padding(13.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    EvidenceThumbnail(state.repository.evidence, files.minByOrNull(Evidence::order), record.category)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(record.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Help(listOfNotNull(data.categoryName(record.category), record.level?.label, dateLabel(record).takeIf(String::isNotEmpty)).joinToString(" · "))
                        val extra = listOfNotNull(record.organization.takeIf(String::isNotBlank),
                            files.size.takeIf { it > 0 }?.let { "$it 个证明" })
                        if (extra.isNotEmpty()) Text(extra.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    StatusPill(files.isNotEmpty())
                }
            }
        }
    }
    if (showFilters) FilterDialog(filter, data, { showFilters = false }) {
        state.filter = it
        showFilters = false
    }
}

@Composable
private fun FilterDialog(filter: RecordFilter, data: Snapshot, dismiss: () -> Unit, apply: (RecordFilter) -> Unit) {
    var selected by remember { mutableStateOf(filter) }
    AlertDialog(
        onDismissRequest = dismiss, title = { Text("筛选记录") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val filters = data.categories.filter { it.active || data.records.any { record -> record.category == it.id } }
                Choice("类别", data.categoryName(selected.category) ?: "不限", listOf(null to "不限") +
                    filters.map { it.id to if (it.active) it.name else "${it.name}（已删除）" }) {
                    selected = selected.copy(category = it)
                }
                Choice("级别", selected.level?.label ?: "不限", listOf(null to "不限") + Level.entries.map { it to it.label }) {
                    selected = selected.copy(level = it)
                }
                Choice(
                    "学期", selected.termId?.let(data::termName) ?: "不限",
                    listOf(null to "不限", RecordFilter.NO_TERM to "未归入学期") + data.terms.map { it.id to it.name }
                ) { selected = selected.copy(termId = it) }
                Choice("状态", selected.state.label, ArchiveState.entries.map { it to it.label }) { selected = selected.copy(state = it) }
            }
        },
        dismissButton = { TextButton({ apply(RecordFilter(query = filter.query)) }) { Text("清除筛选") } },
        confirmButton = { TextButton({ apply(selected) }) { Text("应用筛选") } }
    )
}

@Composable
internal fun EditRecord(
    state: AppState, data: Snapshot, draft: RecordDraft, extras: Boolean, actions: EvidenceActions, selectAiPhoto: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Page(Modifier.weight(1f)) {
            if (extras) {
                Field(draft.organization, "颁发／组织单位（选填）", TEXT_LIMIT) { input -> state.updateDraft { it.copy(organization = input) } }
                Field(draft.result, "结果（选填）", TEXT_LIMIT) { input -> state.updateDraft { it.copy(result = input) } }
                Field(draft.notes, "备注（选填）", TEXT_LIMIT, 3) { input -> state.updateDraft { it.copy(notes = input) } }
                Panel {
                    Heading("证明附件（选填）")
                    if (draft.files.isEmpty()) Help("可以添加照片、PDF 或 Word。")
                    draft.files.forEachIndexed { index, item ->
                        key(item.id) { EditableAttachment(state, item, index, draft.files.lastIndex) }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(actions.camera) { Text("拍照") }
                        OutlinedButton(actions.photos) { Text("相册") }
                        OutlinedButton(actions.files) { Text("导入文件") }
                    }
                }
            } else {
                if (!state.editingExisting) AiEntry(state, data, draft, selectAiPhoto)
                if (state.reviewingAi) Panel {
                    Heading("请核对识别结果")
                    Help("请核对填入的信息。单位、结果和备注可在补充信息中查看。")
                }
                Field(draft.title, "名称 *", TITLE_LIMIT) { input -> state.updateDraft { it.copy(title = input) } }
                Choice("类别 *", data.categoryName(draft.category) ?: "请选择", data.categories
                    .filter { it.active || it.id == draft.category }.map { it.id to it.name }) { category ->
                    state.updateDraft { it.copy(category = category) }
                }
                TextButton(state::openCategories, enabled = !state.aiWorking) { Text("管理类别") }
                Choice("级别（选填）", draft.level?.label ?: "未选择", listOf(null to "未选择") + Level.entries.map { it to it.label }) { level ->
                    state.updateDraft { it.copy(level = level) }
                }
                Panel {
                    Heading("时间（选填）")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(!draft.ranged, { state.updateDraft { it.copy(ranged = false) } }, { Text("单日") })
                        FilterChip(draft.ranged, { state.updateDraft { it.copy(ranged = true) } }, { Text("时间段") })
                    }
                    DayField(if (draft.ranged) "开始日期" else "日期", draft.start) { day ->
                        state.updateDraft { it.copy(start = day) }
                    }
                    if (draft.ranged) DayField("结束日期", draft.end, draft.start) { day ->
                        state.updateDraft { it.copy(end = day) }
                    }
                    if (draft.start.isNotEmpty() || draft.end.isNotEmpty()) {
                        TextButton({ state.updateDraft { it.copy(start = "", end = "", ranged = false) } }) { Text("清除日期") }
                    }
                }
                val termOptions = listOf(null to "未归入学期") + data.terms.map { it.id to it.name }
                Choice("学期", data.termName(draft.termId), termOptions) { id ->
                    state.updateDraft { it.copy(termId = id) }
                }
            }
        }
        HorizontalDivider()
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(state::storeDraft, Modifier.fillMaxWidth(), enabled = !state.aiWorking) { Text("保存记录") }
            if (!extras) OutlinedButton({ state.screen = Screen.EXTRAS }, Modifier.fillMaxWidth(), enabled = !state.aiWorking) {
                Text("补充信息与证明（可选）")
            }
        }
    }
}

@Composable
private fun EditableAttachment(state: AppState, item: Evidence, index: Int, last: Int) {
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    var name by remember(item.name) { mutableStateOf(item.name) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { AttachmentRow(state.repository.evidence, item) { state.preview(item) } }
        Box {
            IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "管理附件") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text("重命名") }, { menu = false; name = item.name; rename = true })
                DropdownMenuItem({ Text("上移") }, { menu = false; state.moveFile(item, -1) }, enabled = index > 0)
                DropdownMenuItem({ Text("下移") }, { menu = false; state.moveFile(item, 1) }, enabled = index < last)
                DropdownMenuItem({ Text("删除附件") }, { menu = false; delete = true })
            }
        }
    }
    if (rename) AlertDialog(
        onDismissRequest = { rename = false }, title = { Text("重命名附件") },
        text = { Field(name, "附件名称", FILE_NAME_LIMIT) { name = it } },
        confirmButton = { TextButton({ state.renameFile(item, name); rename = false }, enabled = name.isNotBlank()) { Text("确定") } },
        dismissButton = { TextButton({ rename = false }) { Text("取消") } }
    )
    if (delete) Confirm(
        "删除这个附件？", "保存记录后将移除拾证内的这份副本。记录和外部源文件会保留。",
        "删除附件", { delete = false }, { delete = false; state.deleteFile(item) }
    )
}

@Composable
internal fun RecordDetail(state: AppState, data: Snapshot, record: Record) {
    var delete by remember(record.id) { mutableStateOf(false) }
    val files = data.filesFor(record.id)
    Page {
        Panel {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Text(record.title, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                StatusPill(files.isNotEmpty())
            }
            DetailLine("类别", data.categoryName(record.category).orEmpty())
            DetailLine("级别", record.level?.label.orEmpty())
            DetailLine("时间", dateLabel(record))
            DetailLine("学期", data.termName(record.termId))
            DetailLine("颁发／组织单位", record.organization)
            DetailLine("结果", record.result)
            DetailLine("备注", record.notes)
        }
        Panel {
            Heading("证明附件 · ${files.size}")
            if (files.isEmpty()) Help("还没有证明，可以随时补充。")
            for (file in files) AttachmentRow(state.repository.evidence, file) { state.preview(file) }
            OutlinedButton({ state.editRecord(record, extras = true) }, Modifier.fillMaxWidth()) {
                Text(if (files.isEmpty()) "添加证明" else "管理证明")
            }
        }
        Button({ state.editRecord(record) }, Modifier.fillMaxWidth()) { Text("编辑记录") }
        TextButton({ delete = true }, Modifier.fillMaxWidth()) { Text("删除记录", color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(8.dp))
    }
    if (delete) Confirm(
        "删除这条记录？", "将删除这条记录和拾证内的 ${files.size} 份附件。外部源文件会保留。",
        "删除记录", { delete = false }, { delete = false; state.deleteRecord() }
    )
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(0.35f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value.ifBlank { "未填写" }, Modifier.weight(0.65f), style = MaterialTheme.typography.bodyMedium)
    }
}

private fun dateLabel(record: Record) = record.end?.let { "${record.start} 至 $it" } ?: record.start
