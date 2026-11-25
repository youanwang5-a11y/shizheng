package app.shizheng.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.shizheng.data.parseDate
import java.time.LocalDate
import java.time.ZoneId

@Composable
internal fun Page(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(Modifier.widthIn(max = 620.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

@Composable
internal fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
internal fun Heading(text: String) =
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

@Composable
internal fun Help(text: String) =
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
internal fun PrimaryButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding, content: @Composable RowScope.() -> Unit
) = Button(
    onClick, modifier.heightIn(min = 48.dp), enabled,
    shape = MaterialTheme.shapes.medium, contentPadding = contentPadding, content = content
)

@Composable
internal fun SecondaryButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding, content: @Composable RowScope.() -> Unit
) = OutlinedButton(
    onClick, modifier.heightIn(min = 48.dp), enabled,
    shape = MaterialTheme.shapes.medium,
    border = BorderStroke(1.dp, if (enabled) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant),
    contentPadding = contentPadding, content = content
)

@Composable
internal fun Field(value: String, label: String, limit: Int, lines: Int = 1, maxLines: Int = Int.MAX_VALUE, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, { if (it.length <= limit) onChange(it) },
        Modifier.fillMaxWidth(), label = { Text(label) }, minLines = lines, maxLines = maxLines, singleLine = lines == 1,
        shape = MaterialTheme.shapes.medium, colors = inputColors()
    )
}

@Composable
internal fun <T> Choice(
    label: String, value: String, items: List<Pair<T, String>>, compact: Boolean = false,
    menuAction: Pair<String, () -> Unit>? = null, onSelect: (T) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        if (compact) {
            FormRow(label, Modifier.clickable(role = Role.Button) { open = true }) {
                FormValue(value)
            }
        } else {
            SecondaryButton({ open = true }, Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.weight(1f))
                    Text(value, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp))
                }
            }
        }
        DropdownMenu(open, { open = false }) {
            if (menuAction != null) {
                DropdownMenuItem({ Text(menuAction.first) }, onClick = {
                    open = false
                    menuAction.second()
                })
                HorizontalDivider()
            }
            for ((item, text) in items) {
                DropdownMenuItem({ Text(text) }, onClick = {
                    open = false
                    onSelect(item)
                })
            }
        }
    }
}

@Composable
internal fun FormRow(label: String, modifier: Modifier = Modifier, control: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().then(modifier).heightIn(min = 48.dp).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(label, Modifier.weight(0.31f), style = MaterialTheme.typography.labelLarge)
        Box(Modifier.weight(0.69f)) { control() }
    }
}

@Composable
internal fun FormValue(value: String) {
    Surface(
        shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = 36.dp).padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp))
        }
    }
}

@Composable
internal fun DayField(label: String, value: String, initial: String = "", onPick: (String) -> Unit) {
    val context = LocalContext.current
    SecondaryButton(
        onClick = {
            val date = parseDate(value) ?: parseDate(initial) ?: LocalDate.now()
            val dialog = DatePickerDialog(
                context, { _, year, month, day -> onPick(LocalDate.of(year, month + 1, day).toString()) },
                date.year, date.monthValue - 1, date.dayOfMonth
            )
            val zone = ZoneId.systemDefault()
            dialog.datePicker.minDate = LocalDate.of(1900, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
            dialog.datePicker.maxDate = LocalDate.of(2200, 12, 31).atStartOfDay(zone).toInstant().toEpochMilli()
            dialog.show()
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f))
            Text(value.ifBlank { "选择日期" }, Modifier.weight(1f))
            Icon(Icons.Default.DateRange, null, Modifier.size(18.dp))
        }
    }
}

@Composable
internal fun StatusPill(archived: Boolean) {
    val foreground = if (archived) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary
    Surface(
        color = MaterialTheme.colorScheme.surface, contentColor = foreground, shape = MaterialTheme.shapes.extraLarge,
        border = BorderStroke(1.dp, foreground.copy(alpha = 0.72f))
    ) {
        Text(
            if (archived) "已归档" else "待补证明",
            Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelMedium, maxLines = 1
        )
    }
}

@Composable
internal fun Confirm(title: String, text: String, action: String, dismiss: () -> Unit, confirm: () -> Unit, cancel: String = "取消") {
    AlertDialog(
        onDismissRequest = dismiss, title = { Text(title) }, text = { Text(text) },
        confirmButton = { TextButton(confirm) { Text(action) } },
        dismissButton = { TextButton(dismiss) { Text(cancel) } }
    )
}

@Composable
internal fun MenuItem(title: String, supporting: String, onClick: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Help(supporting)
            }
            Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}
