package com.rohitkukreja.smartplant.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rohitkukreja.smartplant.alerts.Alert
import com.rohitkukreja.smartplant.alerts.AlertType
import com.rohitkukreja.smartplant.alerts.Severity
import com.rohitkukreja.smartplant.ui.theme.Danger
import com.rohitkukreja.smartplant.ui.theme.Ok
import com.rohitkukreja.smartplant.ui.theme.Warn

@Composable
fun SectionCard(
    title: String,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    ElevatedCard(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(icon, null, tint = iconTint, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

fun severityColor(s: Severity): Color = when (s) {
    Severity.CRITICAL -> Danger
    Severity.WARNING -> Warn
    Severity.INFO -> Color(0xFF0277BD)
}

@Composable
fun AlertBanner(
    alert: Alert,
    onDismiss: (() -> Unit)? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val c = severityColor(alert.type.severity)
    Surface(
        color = c.copy(alpha = 0.12f),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(
                when (alert.type.severity) {
                    Severity.CRITICAL -> Icons.Filled.Error
                    Severity.WARNING -> Icons.Filled.Warning
                    Severity.INFO -> Icons.Filled.Info
                },
                null, tint = c, modifier = Modifier.padding(top = 2.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(alert.type.title, fontWeight = FontWeight.SemiBold, color = c)
                Text(alert.message, style = MaterialTheme.typography.bodySmall)
                if (actionLabel != null && onAction != null) {
                    Spacer(Modifier.height(6.dp))
                    FilledTonalButton(onClick = onAction) { Text(actionLabel) }
                }
            }
            if (onDismiss != null && alert.type != AlertType.TANK_EMPTY) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Close, "Dismiss", modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/** 270° gauge with the plant's ideal band highlighted. */
@Composable
fun MoistureGauge(value: Int, min: Int, max: Int, modifier: Modifier = Modifier) {
    val color = when {
        value < min - 15 -> Danger
        value < min -> Warn
        value > max + 15 -> Danger
        value > max -> Warn
        else -> Ok
    }
    val track = MaterialTheme.colorScheme.surfaceVariant
    val band = Ok.copy(alpha = 0.30f)
    Box(modifier.size(190.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(10.dp)) {
            val stroke = 18.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val tl = Offset(inset, inset)
            drawArc(track, 135f, 270f, false, tl, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(band, 135f + 270f * min / 100f, 270f * (max - min) / 100f, false, tl, arcSize,
                style = Stroke(stroke, cap = StrokeCap.Butt))
            drawArc(color, 135f, 270f * value.coerceIn(0, 100) / 100f, false, tl,
                Size(arcSize.width, arcSize.height), style = Stroke(stroke * 0.55f, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$value%", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = color)
            Text("soil moisture", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
fun StatusDot(color: Color) {
    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
}

@Composable
fun LabeledValue(label: String, value: String, sub: String? = null, valueColor: Color = Color.Unspecified) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = valueColor)
        if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
