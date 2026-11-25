package app.shizheng.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.shizheng.R
import app.shizheng.data.*

@Composable
internal fun RecordList(
    state: AppState, data: Snapshot, results: List<Record>, scroll: LazyListState,
    modifier: Modifier, bottomClearance: Dp
) {
    var showFilters by remember { mutableStateOf(false) }
    val filter = state.filter
    val selection = state.selectedRecordIds
    val filesByRecord = remember(data.evidence) {
        data.evidence.groupBy(Evidence::recordId).mapValues { (_, files) -> files.sortedBy(Evidence::order) }
    }

    LazyColumn(
        modifier.fillMaxSize(),
        state = scroll,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 12.dp + bottomClearance)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    filter.query, { state.changeFilter(filter.copy(query = it.take(200))) },
                    Modifier.fillMaxWidth(), singleLine = true, shape = MaterialTheme.shapes.medium,
                    colors = inputColors(),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    placeholder = { Text("搜索名称、单位或备注") },
                    leadingIcon = { Icon(Icons.Default.Search, "搜索记录") },
                    trailingIcon = {
                        if (filter.query.isNotEmpty()) TextButton({ state.changeFilter(filter.copy(query = "")) }) { Text("清空") }
                    }
                )
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for (status in ArchiveState.entries) {
                        RecordToolbarControl(status.label, selected = filter.state == status) {
                            state.changeFilter(filter.copy(state = status))
                        }
                    }
                    RecordToolbarControl("筛选", icon = R.drawable.ic_filter) { showFilters = true }
                    if (selection == null) RecordToolbarControl("多选", icon = R.drawable.ic_multiselect, enabled = results.isNotEmpty()) {
                        state.startSelectingRecords()
                    }
                }
                val conditions = listOf(
                    filter.termIds.map(data::termName),
                    filter.categories.mapNotNull(data::categoryName),
                    filter.levels.map(Level::label)
                ).filter { it.isNotEmpty() }
                if (conditions.isNotEmpty()) Text(
                    conditions.joinToString(" · ") { it.joinToString("、") },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
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
                        TextButton({ state.changeFilter(RecordFilter()) }) { Text("清除搜索和筛选") }
                    }
                }
            }
        }
        items(results, key = Record::id) { record ->
            val files = filesByRecord[record.id].orEmpty()
            val selected = selection?.contains(record.id) == true
            val action = if (selection == null) {
                Modifier.combinedClickable(
                    onClick = { state.openRecord(record.id) },
                    onLongClickLabel = "选择记录",
                    onLongClick = { state.startSelectingRecords(record.id) }
                )
            } else {
                Modifier.toggleable(selected, role = Role.Checkbox, onValueChange = { state.toggleRecordSelection(record.id) })
            }
            Surface(
                modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)),
                shadowElevation = 2.dp
            ) {
                Row(Modifier.then(action).padding(10.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (selection != null) Checkbox(selected, onCheckedChange = null, modifier = Modifier.align(Alignment.CenterVertically))
                    else CategoryIllustration(record.category)
                    BoxWithConstraints(Modifier.weight(1f)) {
                        val inlineStatus = maxWidth >= 214.dp && LocalDensity.current.fontScale <= 1.25f
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.Top) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        record.title, style = MaterialTheme.typography.titleMedium,
                                        fontSize = 18.sp, lineHeight = 23.sp, maxLines = 2, overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        listOfNotNull(data.categoryName(record.category), record.level?.label, shortDateLabel(record)).joinToString(" · "),
                                        style = recordMetadataStyle, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (inlineStatus) StatusPill(files.isNotEmpty())
                                if (selection == null) Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                                    Modifier.size(17.dp).align(Alignment.CenterVertically)
                                )
                            }
                            if (!inlineStatus) StatusPill(files.isNotEmpty())
                            if (files.isNotEmpty()) {
                                LazyRow(
                                    Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    items(files, key = Evidence::id) { file ->
                                        EvidenceThumbnail(
                                            state.repository.evidence, file,
                                            Modifier.size(width = if (file.kind == EvidenceKind.PHOTO) 64.dp else 48.dp, height = 48.dp),
                                            onClick = if (selection == null) ({ state.preview(file) }) else null,
                                            onLongClick = if (selection == null) ({ state.startSelectingRecords(record.id) }) else null
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (showFilters) FilterDialog(filter, data, { showFilters = false }) {
        state.changeFilter(it)
        showFilters = false
    }
}

@Composable
private fun RecordToolbarControl(
    label: String, selected: Boolean? = null, icon: Int? = null, enabled: Boolean = true, onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val shape = if (selected == null) MaterialTheme.shapes.small else MaterialTheme.shapes.extraLarge
    val action = if (selected == null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    else Modifier.selectable(selected, role = Role.Tab, onClick = onClick)
    Box(
        Modifier.heightIn(min = 48.dp).clip(shape).then(action),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = shape,
            color = when (selected) {
                true -> scheme.primary
                false -> scheme.surface
                null -> scheme.surfaceVariant
            },
            contentColor = when {
                !enabled -> scheme.onSurface.copy(alpha = 0.38f)
                selected == true -> scheme.onPrimary
                else -> scheme.onSurfaceVariant
            },
            border = if (selected == true) null else BorderStroke(1.dp, scheme.outlineVariant)
        ) {
            Row(
                Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 34.dp).padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (icon != null) Icon(painterResource(icon), null, Modifier.size(13.dp))
                Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun CategoryIllustration(category: String) {
    val illustration = when (category) {
        Category.COMPETITION.id -> R.drawable.illustration_competition
        Category.VOLUNTEER.id -> R.drawable.illustration_volunteer
        Category.CULTURE.id -> R.drawable.illustration_culture
        Category.PRACTICE.id -> R.drawable.illustration_practice
        Category.RESEARCH.id -> R.drawable.illustration_research
        Category.HONOR.id -> R.drawable.illustration_honor
        Category.TRAINING.id, Category.ACADEMIC.id -> R.drawable.illustration_training
        Category.OTHER.id -> R.drawable.illustration_other
        else -> R.drawable.illustration_custom
    }
    Image(
        painterResource(illustration), null, Modifier.size(70.dp).clip(MaterialTheme.shapes.small),
        contentScale = ContentScale.Crop
    )
}

@Composable
private fun FilterDialog(filter: RecordFilter, data: Snapshot, dismiss: () -> Unit, apply: (RecordFilter) -> Unit) {
    var selected by remember { mutableStateOf(filter) }
    val years = remember(data.terms) { data.terms.academicYearOptions() }
    val categoryOptions = remember(data.categories, data.records) {
        val used = data.records.mapTo(hashSetOf(), Record::category)
        data.categories.filter { it.active || it.id in used }
            .map { it.id to if (it.active) it.name else "${it.name}（已删除）" }
    }
    AlertDialog(
        onDismissRequest = dismiss, title = { Text("筛选记录") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(bottom = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Help("同一组可勾选多项，匹配任一项即可。未勾选的组不限。")
                    if (years.isNotEmpty()) {
                        Column {
                            Heading("学年")
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                for ((termIds, name) in years) {
                                    val included = selected.termIds.containsAll(termIds)
                                    RecordFilterChip("${name}学年", included) {
                                        val terms = if (included) selected.termIds - termIds else selected.termIds + termIds
                                        selected = selected.copy(termIds = terms)
                                    }
                                }
                            }
                        }
                    }
                    FilterOptions("学期", selected.termIds,
                        data.terms.map { it.id to it.name } + (RecordFilter.NO_TERM to "未归入学期")) {
                        selected = selected.copy(termIds = it)
                    }
                    FilterOptions("类别", selected.categories, categoryOptions) { selected = selected.copy(categories = it) }
                    FilterOptions("级别", selected.levels, Level.entries.map { it to it.label }) { selected = selected.copy(levels = it) }
                    Choice("状态", selected.state.label, ArchiveState.entries.map { it to it.label }) { selected = selected.copy(state = it) }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        },
        dismissButton = { TextButton({ apply(RecordFilter(query = filter.query)) }) { Text("清除筛选") } },
        confirmButton = { PrimaryButton({ apply(selected) }) { Text("应用筛选") } }
    )
}

@Composable
private fun <T> FilterOptions(title: String, selected: Set<T>, options: List<Pair<T, String>>, update: (Set<T>) -> Unit) {
    if (options.isEmpty()) return
    Column {
        Heading(title)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((id, label) in options) {
                val included = id in selected
                RecordFilterChip(label, included) { update(if (included) selected - id else selected + id) }
            }
        }
    }
}

@Composable
private fun RecordFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = {
            Box(Modifier.size(FilterChipDefaults.IconSize), contentAlignment = Alignment.Center) {
                if (selected) Icon(Icons.Default.Check, null)
            }
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = scheme.primary,
            selectedLabelColor = scheme.onPrimary,
            selectedLeadingIconColor = scheme.onPrimary
        )
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
                        SecondaryButton(actions.camera) { Text("拍照") }
                        SecondaryButton(actions.photos) { Text("相册") }
                        SecondaryButton(actions.files) { Text("导入文件") }
                    }
                }
            } else {
                if (!state.editingExisting) AiEntry(state, data, draft, selectAiPhoto)
                if (state.reviewingAi) Panel {
                    Heading("请核对识别结果")
                    Help("请核对填入的信息。单位、结果和备注可在补充信息中查看。")
                }
                RecordForm(state, data, draft)
            }
        }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(Modifier.widthIn(max = 620.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PrimaryButton(state::storeDraft, Modifier.fillMaxWidth(), enabled = !state.aiWorking) { Text("保存记录") }
                if (!extras) SecondaryButton(state::openExtrasFromEditor, Modifier.fillMaxWidth(), enabled = !state.aiWorking) {
                    Text("补充信息与证明（可选）")
                }
            }
        }
    }
}

@Composable
private fun RecordForm(state: AppState, data: Snapshot, draft: RecordDraft) {
    val colors = MaterialTheme.colorScheme
    val divider = colors.outlineVariant.copy(alpha = 0.64f)
    Surface(
        shape = MaterialTheme.shapes.large, color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            FormRow("名称 *") {
                val style = MaterialTheme.typography.bodyMedium
                val shape = MaterialTheme.shapes.small
                val interaction = remember { MutableInteractionSource() }
                val focused by interaction.collectIsFocusedAsState()
                BasicTextField(
                    value = draft.title,
                    onValueChange = { if (it.length <= TITLE_LIMIT) state.updateDraft { d -> d.copy(title = it) } },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).semantics { contentDescription = "名称 *" },
                    singleLine = true, textStyle = style.copy(color = colors.onSurface),
                    cursorBrush = SolidColor(colors.primary), interactionSource = interaction,
                    decorationBox = { input ->
                        Box(
                            Modifier.background(colors.surfaceVariant, shape)
                                .border(1.dp, if (focused) colors.primary else colors.outlineVariant, shape)
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (draft.title.isEmpty()) Text("请输入经历名称", style = style, color = colors.onSurfaceVariant)
                            input()
                        }
                    }
                )
            }
            HorizontalDivider(color = divider)
            Choice(
                "类别 *", data.categoryName(draft.category) ?: "请选择类别",
                data.categories.filter { it.active || it.id == draft.category }.map { it.id to it.name },
                compact = true, menuAction = if (state.aiWorking) null else ("管理类别" to state::openCategories)
            ) { category -> state.updateDraft { it.copy(category = category) } }
            HorizontalDivider(color = divider)
            Choice(
                "级别 (选填)", draft.level?.label ?: "请选择级别",
                listOf(null to "未选择") + Level.entries.map { it to it.label }, compact = true
            ) { level -> state.updateDraft { it.copy(level = level) } }
            HorizontalDivider(color = divider)
            DateEntry(draft, state::updateDraft)
            HorizontalDivider(color = divider)
            Choice(
                "学期", data.termName(draft.termId),
                listOf(null to "未归入学期") + data.terms.map { it.id to it.name }, compact = true
            ) { id -> state.updateDraft { it.copy(termId = id) } }
        }
    }
}

@Composable
private fun DateEntry(draft: RecordDraft, update: ((RecordDraft) -> RecordDraft) -> Unit) {
    var show by remember { mutableStateOf(false) }
    val current = when {
        draft.start.isEmpty() -> "请选择时间"
        !draft.ranged || draft.end.isEmpty() -> draft.start
        else -> "${draft.start} 至 ${draft.end}"
    }
    FormRow("时间 (选填)", Modifier.clickable(role = Role.Button) { show = true }) { FormValue(current) }
    if (show) AlertDialog(
        onDismissRequest = { show = false },
        title = { Text("选择时间") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!draft.ranged, { update { it.copy(ranged = false) } }, { Text("单日") })
                    FilterChip(draft.ranged, { update { it.copy(ranged = true) } }, { Text("时间段") })
                }
                DayField(if (draft.ranged) "开始日期" else "日期", draft.start) { day -> update { it.copy(start = day) } }
                if (draft.ranged) DayField("结束日期", draft.end, draft.start) { day -> update { it.copy(end = day) } }
            }
        },
        dismissButton = {
            if (draft.start.isNotEmpty() || draft.end.isNotEmpty()) {
                TextButton({ update { it.copy(start = "", end = "", ranged = false) }; show = false }) { Text("清除日期") }
            }
        },
        confirmButton = { TextButton({ show = false }) { Text("完成") } }
    )
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
            SecondaryButton({ state.editRecord(record, extras = true) }, Modifier.fillMaxWidth()) {
                Text(if (files.isEmpty()) "添加证明" else "管理证明")
            }
        }
        PrimaryButton({ state.editRecord(record) }, Modifier.fillMaxWidth()) { Text("编辑记录") }
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

private fun shortDateLabel(record: Record): String? {
    val start = record.start.takeIf(String::isNotEmpty) ?: return null
    val shortStart = start.drop(5).replace('-', '.')
    val end = record.end ?: return shortStart
    return if (start.take(4) == end.take(4)) "$shortStart–${end.drop(5).replace('-', '.')}" else dateLabel(record)
}
