package app.shizheng.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.shizheng.data.Record
import app.shizheng.data.RecordFilter
import app.shizheng.data.Snapshot
import app.shizheng.data.Term
import app.shizheng.data.defaultTerms

@Composable
internal fun RecordExportScreen(data: Snapshot, export: (Set<String>) -> Unit) {
    var scope by rememberSaveable(stateSaver = listSaver<Set<String>, String>(
        save = { it.toList() }, restore = { it.toSet() }
    )) { mutableStateOf(emptySet()) }
    val termsByName = remember(data.terms) { data.terms.associateBy(Term::name) }
    val academicYears = remember(termsByName) {
        defaultTerms.chunked(2).map { pair ->
            pair.mapNotNull { termsByName[it.name]?.id }.toSet() to pair.first().name.removeSuffix("上")
        }.filter { it.first.isNotEmpty() }
    }
    val recordsByTerm = remember(data.records) { data.records.groupBy { it.termId ?: RecordFilter.NO_TERM } }
    val records = remember(scope, recordsByTerm) { scope.flatMap { recordsByTerm[it].orEmpty() } }

    Column(Modifier.fillMaxSize()) {
        Page(Modifier.weight(1f)) {
            Help("选择要导出的学期。ZIP 内含 CSV 记录清单和证明原件。")
            Choice("快速选择学年", academicYears.firstOrNull { it.first == scope }?.second ?: "请选择", academicYears) {
                scope = it
            }
            Panel {
                for ((id, name) in data.terms.map { it.id to it.name } + (RecordFilter.NO_TERM to "未归入学期")) {
                    val selected = id in scope
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                            value = selected, role = Role.Checkbox,
                            onValueChange = { checked -> scope = if (checked) scope + id else scope - id }
                        ),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = selected, onCheckedChange = null)
                        Text(name, Modifier.weight(1f))
                        Help("${recordsByTerm[id].orEmpty().size} 条")
                    }
                }
            }
        }
        HorizontalDivider()
        Button(
            onClick = { export(records.mapTo(hashSetOf(), Record::id)) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            enabled = records.isNotEmpty()
        ) { Text("导出 ${records.size} 条记录") }
    }
}
