package com.wansaw.opensms.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wansaw.opensms.ui.theme.Blue
import com.wansaw.opensms.ui.theme.Indigo
import com.wansaw.opensms.ui.theme.LocalGlass
import kotlin.math.cos
import kotlin.math.sin

/** Slowly drifting colour blobs on a deep gradient: the "light" the glass cards refract. */
@Composable
fun GlassBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val g = LocalGlass.current
    val t = rememberInfiniteTransition(label = "bg")
    val phase by t.animateFloat(0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(42_000, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    Box(modifier.fillMaxSize().background(Brush.verticalGradient(listOf(g.bgTop, g.bgBottom)))) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val r = maxOf(w, h) * 0.62f
            val alpha = if (g.dark) 0.55f else 0.65f
            val anchors = listOf(Offset(0.15f, 0.12f), Offset(0.95f, 0.30f), Offset(0.10f, 0.78f), Offset(0.85f, 0.95f))
            g.blobs.forEachIndexed { i, c ->
                val a = anchors[i]
                val cx = a.x * w + cos(phase + i * 1.7f) * w * 0.10f
                val cy = a.y * h + sin(phase * (1 + i * 0.15f) + i) * h * 0.06f
                drawCircle(
                    brush = Brush.radialGradient(listOf(c.copy(alpha = alpha), c.copy(alpha = 0f)), center = Offset(cx, cy), radius = r * (0.55f + 0.1f * i)),
                    radius = r,
                    center = Offset(cx, cy),
                )
            }
        }
        content()
    }
}

val GlassShape: Shape = RoundedCornerShape(26.dp)

/** Frosted, translucent surface with a light-catching rim, like Apple's "liquid glass". */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = GlassShape,
    strong: Boolean = false,
    tint: Color? = null,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val g = LocalGlass.current
    val base = if (strong) g.fillStrong else g.fill
    val rim = Brush.linearGradient(listOf(g.borderHi, g.borderLo, g.borderHi.copy(alpha = g.borderHi.alpha * 0.35f)), start = Offset.Zero, end = Offset(600f, 900f))
    val sheen = Brush.verticalGradient(listOf(Color.White.copy(alpha = if (g.dark) 0.10f else 0.35f), Color.Transparent), endY = 260f)
    var m = modifier.clip(shape).background(base).background(sheen)
    if (tint != null) m = m.background(tint.copy(alpha = if (g.dark) 0.16f else 0.14f))
    m = m.border(1.dp, rim, shape)
    if (onClick != null) {
        val haptic = LocalHapticFeedback.current
        m = m.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        }
    }
    Column(m.padding(contentPadding), content = content)
}

@Composable
fun GlassDivider() {
    HorizontalDivider(color = LocalGlass.current.divider)
}

enum class ButtonKind { Primary, Glass, Danger }

@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Primary,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val g = LocalGlass.current
    val shape = RoundedCornerShape(50)
    val haptic = LocalHapticFeedback.current
    val bg: Brush = when (kind) {
        ButtonKind.Primary -> Brush.linearGradient(listOf(Blue, Indigo))
        ButtonKind.Danger -> Brush.linearGradient(listOf(Color(0xFFFF453A), Color(0xFFFF2D55)))
        ButtonKind.Glass -> Brush.linearGradient(listOf(g.fillStrong, g.fill))
    }
    val fg = if (kind == ButtonKind.Glass) g.textPrimary else Color.White
    Row(
        modifier
            .heightIn(min = 52.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(shape)
            .background(bg)
            .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.5f), Color.White.copy(alpha = 0.06f))), shape)
            .clickable(enabled = enabled && !loading, interactionSource = remember { MutableInteractionSource() }, indication = null) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(horizontal = 24.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(18.dp), color = fg, strokeWidth = 2.dp)
        else if (icon != null) Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp))
        if (loading || icon != null) Spacer(Modifier.width(8.dp))
        Text(text, color = fg, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

@Composable
fun StatusChip(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.18f)).border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun GlassSwitch(checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedTrackColor = com.wansaw.opensms.ui.theme.Green,
            checkedThumbColor = Color.White,
            checkedBorderColor = Color.Transparent,
            uncheckedTrackColor = LocalGlass.current.fillStrong,
            uncheckedThumbColor = Color.White,
            uncheckedBorderColor = LocalGlass.current.borderLo,
        ),
    )
}

@Composable
fun SwitchRow(title: String, subtitle: String? = null, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = LocalGlass.current.textSecondary)
        }
        Spacer(Modifier.width(12.dp))
        GlassSwitch(checked, onChange, enabled)
    }
}

@Composable
fun GlassField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    visual: VisualTransformation = VisualTransformation.None,
    trailing: @Composable (() -> Unit)? = null,
    error: String? = null,
) {
    val g = LocalGlass.current
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = singleLine,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        trailingIcon = trailing,
        visualTransformation = visual,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        shape = RoundedCornerShape(18.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = g.fill,
            unfocusedContainerColor = g.fill,
            focusedBorderColor = Blue,
            unfocusedBorderColor = g.borderLo.copy(alpha = 0.5f),
            focusedTextColor = g.textPrimary,
            unfocusedTextColor = g.textPrimary,
            focusedLabelColor = Blue,
            unfocusedLabelColor = g.textSecondary,
            cursorColor = Blue,
        ),
    )
}

/** iOS-style segmented control. */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier, enabled: (T) -> Boolean = { true }) {
    val g = LocalGlass.current
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(g.fill).border(1.dp, g.borderLo, RoundedCornerShape(16.dp)).padding(3.dp)) {
        options.forEach { (v, label) ->
            val sel = v == selected
            val on = enabled(v)
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(13.dp))
                    .background(if (sel) Brush.linearGradient(listOf(Blue, Indigo)) else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent)))
                    .clickable(enabled = on, interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(v) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (sel) Color.White else g.textPrimary.copy(alpha = if (on) 1f else 0.4f),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
fun ScreenTitle(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.padding(top = 8.dp, bottom = 4.dp)) {
        Text(title, style = MaterialTheme.typography.displaySmall)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = LocalGlass.current.textSecondary)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier.padding(start = 6.dp, top = 18.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelMedium,
        color = LocalGlass.current.textSecondary,
        letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified,
    )
}

/** A circular icon on a tinted glass disc. */
@Composable
fun IconBadge(icon: ImageVector, color: Color, size: Dp = 40.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(Brush.linearGradient(listOf(color, color.copy(alpha = 0.7f)))),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(size * 0.55f)) }
}

/** A tappable row inside a card: badge, title, optional value and chevron. */
@Composable
fun NavRow(icon: ImageVector, color: Color, title: String, value: String? = null, onClick: () -> Unit) {
    val g = LocalGlass.current
    Row(
        Modifier.fillMaxWidth().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(icon, color, 36.dp)
        Spacer(Modifier.width(14.dp))
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        if (value != null) Text(value, color = g.textSecondary, style = MaterialTheme.typography.bodyMedium)
        Icon(androidx.compose.material.icons.Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = g.textSecondary)
    }
}

@Composable
fun Modifier.pressScale(pressed: Boolean): Modifier = this.scale(if (pressed) 0.97f else 1f)

val OutlineBorder @Composable get() = BorderStroke(1.dp, LocalGlass.current.borderLo)
