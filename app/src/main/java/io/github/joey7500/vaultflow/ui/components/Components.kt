package io.github.joey7500.vaultflow.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.joey7500.vaultflow.domain.Money
import io.github.joey7500.vaultflow.domain.ingest.AmountParser
import io.github.joey7500.vaultflow.ui.theme.VaultColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Card with a subtle border, used for every section. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val m = if (onClick != null) modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick) else modifier
    Card(
        modifier = m.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = VaultColors.Surface),
        border = BorderStroke(1.dp, VaultColors.Outline),
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = VaultColors.Muted,
        modifier = modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

fun money(minor: Long, decimals: Boolean = false): String = Money.format(minor, showDecimals = decimals)

fun moneyColor(minor: Long): Color = when {
    minor > 0 -> VaultColors.Emerald
    minor < 0 -> VaultColors.Text
    else -> VaultColors.Muted
}

private val dateFmt = DateTimeFormatter.ofPattern("d MMM, HH:mm")
private val dayFmt = DateTimeFormatter.ofPattern("d MMM yyyy")

fun formatDateTime(millis: Long): String = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(dateFmt)
fun formatDay(millis: Long): String = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(dayFmt)

/** "1 234,50" / "1234.5" / "1 234" -> minor units, or null. */
fun parseMoneyInput(text: String): Long? {
    val cleaned = text.trim().removeSuffix("Kč").removeSuffix("CZK").trim()
    if (cleaned.isEmpty()) return null
    return AmountParser.parseNumber(cleaned)
}

fun minorToInput(minor: Long): String =
    if (minor % 100 == 0L) (minor / 100).toString() else "%d,%02d".format(minor / 100, kotlin.math.abs(minor % 100))

@Composable
fun MoneyField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        suffix = { Text("Kč") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier.fillMaxWidth(),
    )
}

/** Thin rounded progress bar; turns rose when over budget. */
@Composable
fun BudgetBar(progress: Float, over: Boolean, color: Color = VaultColors.Emerald, modifier: Modifier = Modifier) {
    val fill = progress.coerceIn(0f, 1f)
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(VaultColors.SurfaceHigh),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fill)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(if (over) VaultColors.Rose else color),
        )
    }
}

@Composable
fun StatTile(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(VaultColors.SurfaceHigh)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = VaultColors.Muted)
        Spacer(Modifier.height(2.dp))
        Text(value, style = MaterialTheme.typography.titleSmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun Dot(color: Color, size: Dp = 10.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

@Composable
fun Pill(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** Donut chart from (value, color) slices. */
@Composable
fun DonutChart(slices: List<Pair<Float, Color>>, modifier: Modifier = Modifier, thickness: Dp = 22.dp) {
    val total = slices.sumOf { it.first.toDouble() }.toFloat()
    Canvas(modifier) {
        val strokePx = thickness.toPx()
        val diameter = size.minDimension - strokePx
        val topLeft = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
        if (total <= 0f) {
            drawArc(VaultColors.SurfaceHigh, 0f, 360f, false, topLeft, Size(diameter, diameter), style = Stroke(strokePx))
            return@Canvas
        }
        var start = -90f
        val gap = if (slices.count { it.first > 0f } > 1) 2f else 0f
        slices.filter { it.first > 0f }.forEach { (value, color) ->
            val sweep = value / total * 360f
            drawArc(color, start + gap / 2, (sweep - gap).coerceAtLeast(0.5f), false, topLeft, Size(diameter, diameter), style = Stroke(strokePx, cap = StrokeCap.Butt))
            start += sweep
        }
    }
}

/** Minimal area line chart for net-worth history. */
@Composable
fun LineChart(values: List<Long>, modifier: Modifier = Modifier, color: Color = VaultColors.Emerald) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val min = values.min().toFloat()
        val max = values.max().toFloat()
        val range = (max - min).takeIf { it > 0f } ?: 1f
        val stepX = size.width / (values.size - 1)
        fun y(v: Long) = size.height - ((v - min) / range) * (size.height * 0.85f) - size.height * 0.075f
        val line = Path()
        values.forEachIndexed { i, v -> if (i == 0) line.moveTo(0f, y(v)) else line.lineTo(i * stepX, y(v)) }
        val area = Path().apply {
            addPath(line)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), Color.Transparent)))
        drawPath(line, color, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
        drawCircle(color, 4.dp.toPx(), Offset((values.size - 1) * stepX, y(values.last())))
    }
}

@Composable
fun LabeledRow(label: String, value: String, valueColor: Color = VaultColors.Text, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = VaultColors.Muted)
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor)
    }
}

/** "Bank check 10 Nov 17:00 ✓" or "⚠ off by 10 Kč" under an account. */
@Composable
fun BalanceCheckLine(account: io.github.joey7500.vaultflow.domain.model.Account) {
    val at = account.lastCheckAtMillis ?: return
    val diff = account.lastCheckDiffMinor ?: 0L
    val ok = kotlin.math.abs(diff) < 100 // within 1 Kč
    Text(
        if (ok) "✓ Matched the bank's balance at ${formatDateTime(at)}"
        else "⚠ At ${formatDateTime(at)} the app was off by ${money(diff, decimals = true)} and was corrected (a payment may be missing)",
        style = MaterialTheme.typography.bodySmall,
        color = if (ok) VaultColors.Emerald else VaultColors.Amber,
    )
}

/** Short label of which sources reported a transaction: "Wallet · App · E-mail". */
fun evidenceLabel(evidence: Set<io.github.joey7500.vaultflow.domain.model.TransactionSource>): String =
    evidence.mapNotNull {
        when (it) {
            io.github.joey7500.vaultflow.domain.model.TransactionSource.GOOGLE_WALLET -> "Wallet"
            io.github.joey7500.vaultflow.domain.model.TransactionSource.BANK_NOTIFICATION -> "App"
            io.github.joey7500.vaultflow.domain.model.TransactionSource.EMAIL -> "E-mail ✓"
            io.github.joey7500.vaultflow.domain.model.TransactionSource.INFERRED -> "expected"
            io.github.joey7500.vaultflow.domain.model.TransactionSource.MANUAL -> "manual"
            else -> null
        }
    }.joinToString(" · ")
