package dev.zain.znkeyboard.liquidnav

import android.graphics.Paint
import android.graphics.RuntimeShader
import android.view.RoundedCorner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import dev.zain.znkeyboard.constants.LiquidNavDimensions
import dev.zain.znkeyboard.constants.LiquidNavMotion
import kotlin.math.min
import kotlin.math.roundToInt

@Immutable
data class LiquidNavItem(val label: String, val iconResId: Int)

/**
 * Floating nav bar whose selected item is a detached accent pill. Changing the selection pinches the new
 * item off its group like a drop and lets the old one coalesce back, driven by [LiquidNavModel] and drawn
 * by an AGSL signed-distance shader. Icons and labels are ordinary composables layered above the shape.
 */
@Composable
fun LiquidNavBar(
    items: List<LiquidNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    selectedContainerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    selectedContentColor: Color = MaterialTheme.colorScheme.onPrimary,
    borderColor: Color = MaterialTheme.colorScheme.outlineVariant,
) {
    val count = items.size
    require(count in 1..LiquidNavMotion.MAX_SLOTS) { "LiquidNavBar supports 1..${LiquidNavMotion.MAX_SLOTS} items" }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val visualSelected = if (rtl) count - 1 - selectedIndex else selectedIndex
    val model = remember { LiquidNavModel() }
    val renderer = remember { LiquidNavRenderer() }
    val frame = remember { mutableLongStateOf(0L) }
    val view = LocalView.current
    val fallbackRadiusPx = with(LocalDensity.current) { LiquidNavDimensions.FALLBACK_OUTER_CORNER_RADIUS.toPx() }
    var outerBottomLeft by remember { mutableFloatStateOf(fallbackRadiusPx) }
    var outerBottomRight by remember { mutableFloatStateOf(fallbackRadiusPx) }

    LaunchedEffect(model, visualSelected) {
        model.select(visualSelected)
        var last = -1L
        val onFrame: (Long) -> Boolean = { now ->
            // Same source Compose animations use for the system animator duration scale; 0 means "jump".
            val scale = coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
            if (scale == 0f) {
                model.snapToTarget()
            } else if (last >= 0L) {
                model.step(((now - last) / scale).toLong())
            }
            last = now
            frame.longValue = now
            !model.isAtRest
        }
        while (withFrameNanos(onFrame)) Unit
    }

    Layout(
        content = {
            items.forEachIndexed { index, item ->
                LiquidNavSlot(
                    item = item,
                    selected = index == selectedIndex,
                    slot = if (rtl) count - 1 - index else index,
                    model = model,
                    frame = frame,
                    contentColor = contentColor,
                    selectedContentColor = selectedContentColor,
                    onClick = { onSelect(index) },
                )
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .height(LiquidNavDimensions.BAR_HEIGHT)
            .selectableGroup()
            .onGloballyPositioned { coordinates ->
                val r = coordinates.size.height * 0.5f
                val position = coordinates.positionInWindow()
                val left = position.x
                val right = position.x + coordinates.size.width
                val bottom = position.y + coordinates.size.height
                val insets = view.rootWindowInsets
                val bl = insets?.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.takeIf { it.radius > 0 }
                val br = insets?.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)?.takeIf { it.radius > 0 }
                // Concentric with the screen corner: share its centre, so radius = screen radius − distance to edge.
                outerBottomLeft = if (bl == null) {
                    min(fallbackRadiusPx, r)
                } else {
                    min(bl.center.x - left, bottom - bl.center.y).coerceIn(0f, r)
                }
                outerBottomRight = if (br == null) {
                    min(fallbackRadiusPx, r)
                } else {
                    min(right - br.center.x, bottom - br.center.y).coerceIn(0f, r)
                }
            }
            .drawBehind {
                frame.longValue
                renderer.draw(
                    scope = this,
                    model = model,
                    outerBottomLeft = outerBottomLeft,
                    outerBottomRight = outerBottomRight,
                    neutral = containerColor.toArgb(),
                    accent = selectedContainerColor.toArgb(),
                    border = borderColor.toArgb(),
                    borderWidth = LiquidNavDimensions.BORDER_WIDTH.toPx(),
                )
            },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        model.configure(
            count = count,
            widthPx = width.toFloat(),
            heightPx = height.toFloat(),
            gapPx = LiquidNavDimensions.GROUP_GAP.toPx(),
            selectedWidthPx = LiquidNavDimensions.SELECTED_PILL_WIDTH.toPx(),
            selected = visualSelected,
        )
        model.select(visualSelected)
        val placeables = measurables.mapIndexed { index, measurable ->
            val slot = if (rtl) count - 1 - index else index
            val slotWidth = (model.targetRight(slot) - model.targetLeft(slot)).roundToInt().coerceAtLeast(0)
            measurable.measure(Constraints.fixed(slotWidth, height))
        }
        layout(width, height) {
            frame.longValue
            for (index in placeables.indices) {
                val placeable = placeables[index]
                val slot = if (rtl) count - 1 - index else index
                placeable.place((model.slotCenter(slot) - placeable.width * 0.5f).roundToInt(), 0)
            }
        }
    }
}

@Composable
private fun LiquidNavSlot(
    item: LiquidNavItem,
    selected: Boolean,
    slot: Int,
    model: LiquidNavModel,
    frame: MutableLongState,
    contentColor: Color,
    selectedContentColor: Color,
    onClick: () -> Unit,
) {
    val painter = painterResource(item.iconResId)
    var labelHeight by remember { mutableIntStateOf(0) }
    val iconLabelGapPx = with(LocalDensity.current) { LiquidNavDimensions.ICON_LABEL_GAP.toPx() }
    Box(
        modifier = Modifier
            .selectable(
                selected = selected,
                interactionSource = null,
                indication = ripple(bounded = false, radius = LiquidNavDimensions.RIPPLE_RADIUS),
                role = Role.Tab,
                onClick = onClick,
            )
            .semantics { contentDescription = item.label },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.clearAndSetSemantics {},
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(LiquidNavDimensions.ICON_SIZE)
                    .graphicsLayer {
                        frame.longValue
                        // The label fades out, so the icon slides down to the pill's centre.
                        translationY = model.slotAccent(slot) * (iconLabelGapPx + labelHeight) * 0.5f
                    },
            ) {
                Icon(
                    painter = painter,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(LiquidNavDimensions.ICON_SIZE).graphicsLayer {
                        frame.longValue
                        alpha = 1f - model.slotAccent(slot)
                    },
                )
                Icon(
                    painter = painter,
                    contentDescription = null,
                    tint = selectedContentColor,
                    modifier = Modifier.size(LiquidNavDimensions.ICON_SIZE).graphicsLayer {
                        frame.longValue
                        alpha = model.slotAccent(slot)
                    },
                )
            }
            Spacer(Modifier.height(LiquidNavDimensions.ICON_LABEL_GAP))
            Text(
                text = item.label,
                color = contentColor,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .onSizeChanged { labelHeight = it.height }
                    .graphicsLayer {
                        frame.longValue
                        val accent = model.slotAccent(slot)
                        alpha = (1f - 2f * accent).coerceAtLeast(0f)
                        val scale = 1f - (1f - LiquidNavDimensions.LABEL_SELECTED_SCALE) * accent
                        scaleX = scale
                        scaleY = scale
                    },
            )
        }
    }
}

/** Owns the shader, paint and uniform buffers; a frame only rewrites uniforms. */
private class LiquidNavRenderer {
    private val shader = RuntimeShader(LIQUID_NAV_AGSL)
    private val paint = Paint().apply { shader = this@LiquidNavRenderer.shader }
    private val slots = FloatArray(LiquidNavMotion.MAX_SLOTS * 4)
    private val bridges = FloatArray((LiquidNavMotion.MAX_SLOTS - 1) * 4)
    private var neutral = 0
    private var accent = 0
    private var border = 0
    private var colorsSet = false

    fun draw(
        scope: DrawScope,
        model: LiquidNavModel,
        outerBottomLeft: Float,
        outerBottomRight: Float,
        neutral: Int,
        accent: Int,
        border: Int,
        borderWidth: Float,
    ) {
        if (model.count == 0) return
        if (!colorsSet || neutral != this.neutral || accent != this.accent || border != this.border) {
            shader.setColorUniform("uNeutral", neutral)
            shader.setColorUniform("uAccent", accent)
            shader.setColorUniform("uBorderColor", border)
            this.neutral = neutral
            this.accent = accent
            this.border = border
            colorsSet = true
        }
        model.fillSlots(slots)
        model.fillBridges(bridges)
        val r = model.radius
        shader.setFloatUniform("uBorder", borderWidth)
        shader.setFloatUniform("uCount", model.count.toFloat())
        shader.setFloatUniform("uCy", scope.size.height * 0.5f)
        shader.setFloatUniform("uR", r)
        shader.setFloatUniform("uOuterBL", outerBottomLeft.coerceIn(0f, r))
        shader.setFloatUniform("uOuterBR", outerBottomRight.coerceIn(0f, r))
        shader.setFloatUniform("uSlots", slots)
        shader.setFloatUniform("uBridges", bridges)
        scope.drawContext.canvas.nativeCanvas.drawRect(0f, 0f, scope.size.width, scope.size.height, paint)
    }
}

private const val SLOTS = LiquidNavMotion.MAX_SLOTS

/**
 * Union of per-slot rounded boxes (inner ends are semicircles, outer bottom corners concentric with the
 * screen) and capillary bridges (box minus two circular fillets, see LiquidNavModel.bridgeGeometry).
 * Coverage is the analytic 1px anti-aliased edge of that signed distance, so outlines stay crisp.
 * Accent blends across each junction over the neck half-width, which collapses to the gap once pinched.
 */
private val LIQUID_NAV_AGSL = """
uniform float uCount;
uniform float uCy;
uniform float uR;
uniform float uOuterBL;
uniform float uOuterBR;
uniform float uBorder;
uniform float uSlots[${SLOTS * 4}];
uniform float uBridges[${(SLOTS - 1) * 4}];
layout(color) uniform half4 uNeutral;
layout(color) uniform half4 uAccent;
layout(color) uniform half4 uBorderColor;

float sdSlot(float2 p, float l, float r, float rbl, float rbr) {
    float2 q = float2(p.x - 0.5 * (l + r), p.y - uCy);
    float rad = q.y > 0.0 ? (q.x > 0.0 ? rbr : rbl) : uR;
    float2 d = abs(q) - float2(0.5 * (r - l), uR) + rad;
    return min(max(d.x, d.y), 0.0) + length(max(d, 0.0)) - rad;
}

float sdBridge(float2 p, float xm, float hw, float k, float n) {
    float2 q = float2(p.x - xm, abs(p.y - uCy));
    float hh = n + k;
    float2 d = float2(abs(q.x) - hw, q.y - hh);
    float box = min(max(d.x, d.y), 0.0) + length(max(d, 0.0));
    return max(box, k - length(float2(q.x, q.y - hh)));
}

half4 main(float2 p) {
    float d = 10000.0;
    float a = uSlots[2];
    for (int i = 0; i < $SLOTS; i++) {
        if (float(i) < uCount) {
            float rbl = i == 0 ? uOuterBL : uR;
            float rbr = float(i) == uCount - 1.0 ? uOuterBR : uR;
            d = min(d, sdSlot(p, uSlots[i * 4], uSlots[i * 4 + 1], rbl, rbr));
        }
    }
    for (int j = 0; j < ${SLOTS - 1}; j++) {
        if (float(j) < uCount - 1.0) {
            float xm = uBridges[j * 4];
            float t = uSlots[j * 4 + 3];
            a += (uSlots[j * 4 + 6] - uSlots[j * 4 + 2]) * smoothstep(xm - t, xm + t, p.x);
            float n = uBridges[j * 4 + 3];
            if (n > 0.0) {
                d = min(d, sdBridge(p, xm, uBridges[j * 4 + 1], uBridges[j * 4 + 2], n));
            }
        }
    }
    half coverage = half(clamp(0.5 - d, 0.0, 1.0));
    // Stroke is the band within uBorder px of the outline, so it follows necks as they stretch and pinch.
    half fill = half(clamp(0.5 - d - uBorder, 0.0, 1.0));
    half4 body = mix(uNeutral, uAccent, half(clamp(a, 0.0, 1.0)));
    return mix(uBorderColor, body, fill) * coverage;
}
"""
