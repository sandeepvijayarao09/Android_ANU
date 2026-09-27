package io.github.sandeepvijayarao09.anu.core.ui

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/**
 * A page (DESIGN.md "Layout"): a 56dp bar with the privacy indicator on the right, then a large title
 * that collapses into the bar as a headline once it scrolls away. The indicator is part of every page,
 * so no page can hide it. Pages inside a page have a back button named for where it goes. Content is at
 * most 640dp wide, centered on tablets and foldables.
 */
@Composable
fun AnuPage(
    title: String,
    sentToday: Int,
    onOpenPrivacy: (() -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    back: String? = null,
    onBack: (() -> Unit)? = null,
    listState: LazyListState = rememberLazyListState(),
    actions: @Composable RowScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val colors = AnuTheme.colors
    val threshold = with(LocalDensity.current) { 30.dp.roundToPx() }
    val collapsed by remember(listState, threshold) {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > threshold }
    }
    val scrolled by remember(listState) { derivedStateOf { listState.canScrollBackward } }
    val titleAlpha by animateFloatAsState(if (collapsed) 1f else 0f, tween(180), label = "collapsed title")
    Column(modifier.fillMaxSize().background(colors.background)) {
        PageBar(title, { titleAlpha }, back, onBack, sentToday, onOpenPrivacy, actions)
        HorizontalDivider(thickness = 0.5.dp, color = if (scrolled) colors.separator else Color.Transparent)
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 640.dp).fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 32.dp)) {
                item(key = "anu:title") { LargeTitle(title, Modifier.padding(top = 2.dp, bottom = 12.dp), subtitle) }
                content()
            }
        }
    }
}

/**
 * The bar: back on the left, the collapsed title centered, actions and the privacy indicator on the
 * right. The sides are measured first, so the indicator is never squeezed; the title gets what's left.
 */
@Composable
private fun PageBar(
    title: String,
    titleAlpha: () -> Float,
    back: String?,
    onBack: (() -> Unit)?,
    sentToday: Int,
    onOpenPrivacy: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit,
) {
    val colors = AnuTheme.colors
    Layout(
        content = {
            Box(Modifier.padding(start = 8.dp)) {
                if (back != null && onBack != null) {
                    Row(
                        Modifier
                            .clip(AnuShapes.sm)
                            .clickable(role = Role.Button, onClick = onBack)
                            .clearAndSetSemantics { contentDescription = "Back to $back" }
                            .padding(start = 2.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(AnuIcons.ChevronLeft, contentDescription = null, Modifier.size(24.dp), tint = colors.accent)
                        Text(back, style = AnuTheme.type.body, color = colors.accent, maxLines = 1)
                    }
                }
            }
            // The large title is the heading; this copy is only visual.
            Text(
                title,
                Modifier.graphicsLayer { alpha = titleAlpha() }.clearAndSetSemantics {},
                style = AnuTheme.type.headline,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(Modifier.padding(end = AnuSpacing.xl), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                actions()
                PrivacyIndicator(sentToday, onOpenPrivacy)
            }
        },
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val end = measurables[2].measure(loose)
        val start = measurables[0].measure(loose.copy(maxWidth = (width - end.width).coerceAtLeast(0)))
        val side = maxOf(start.width, end.width)
        val middle = measurables[1].measure(loose.copy(maxWidth = (width - 2 * side - 16.dp.roundToPx()).coerceAtLeast(0)))
        layout(width, height) {
            start.placeRelative(0, (height - start.height) / 2)
            end.placeRelative(width - end.width, (height - end.height) / 2)
            middle.placeRelative((width - middle.width) / 2, (height - middle.height) / 2)
        }
    }
}

/**
 * Going into a page slides it in from the end; going back slides it away (DESIGN.md "Motion": critically
 * damped springs, nothing bounces). With animations off, pages cross-fade.
 */
fun pushTransition(forward: Boolean, reducedMotion: Boolean): ContentTransform {
    if (reducedMotion) return fadeIn(tween(180)) togetherWith fadeOut(tween(180))
    val slide = spring(dampingRatio = 1f, stiffness = 400f, visibilityThreshold = IntOffset.VisibilityThreshold)
    return if (forward) {
        slideInHorizontally(slide) { it } togetherWith slideOutHorizontally(slide) { -it / 4 } + fadeOut(tween(180))
    } else {
        // The page going away stays on top as it slides off.
        (slideInHorizontally(slide) { -it / 4 } + fadeIn(tween(180)) togetherWith slideOutHorizontally(slide) { it }).apply { targetContentZIndex = -1f }
    }
}
