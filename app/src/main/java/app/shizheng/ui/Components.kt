package app.shizheng.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.shizheng.data.parseDate
import java.time.LocalDate
import java.time.ZoneId

private val palette = lightColorScheme(
    primary = Color(0xFF126955), onPrimary = Color.White,
    primaryContainer = Color(0xFFD7F0E4), onPrimaryContainer = Color(0xFF114B3C),
    secondary = Color(0xFF406352), onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDEDE1), onSecondaryContainer = Color(0xFF1D4535),
    background = Color(0xFFF8F9F5), onBackground = Color(0xFF17231D),
    surface = Color.White, onSurface = Color(0xFF17231D),
    surfaceVariant = Color(0xFFEBF0E9), onSurfaceVariant = Color(0xFF46584F),
    surfaceContainer = Color(0xFFF0F4EE), surfaceContainerHigh = Color(0xFFEAF0E8),
    outline = Color(0xFF6D8174), outlineVariant = Color(0xFFD9E3D9)
)

@Composable
internal fun ShizhengTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = palette, content = content)

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
internal fun Field(value: String, label: String, limit: Int, lines: Int = 1, maxLines: Int = Int.MAX_VALUE, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, { if (it.length <= limit) onChange(it) },
        Modifier.fillMaxWidth(), label = { Text(label) }, minLines = lines, maxLines = maxLines, singleLine = lines == 1,
        shape = MaterialTheme.shapes.medium
    )
}

@Composable
internal fun <T> Choice(label: String, value: String, items: List<Pair<T, String>>, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        OutlinedButton({ open = true }, Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f))
                Text(value, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("⌄")
            }
        }
        DropdownMenu(open, { open = false }) {
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
internal fun DayField(label: String, value: String, initial: String = "", onPick: (String) -> Unit) {
    val context = LocalContext.current
    OutlinedButton(
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
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium
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
    val background = if (archived) Color(0xFFDFF2E7) else Color(0xFFFFEDDA)
    val foreground = if (archived) Color(0xFF205C3B) else Color(0xFF8C4B12)
    Surface(color = background, contentColor = foreground, shape = MaterialTheme.shapes.extraLarge) {
        Text(
            if (archived) "已归档" else "待补证明",
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium
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
        shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface
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
