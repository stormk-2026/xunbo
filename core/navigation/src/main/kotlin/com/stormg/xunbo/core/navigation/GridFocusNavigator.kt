package com.stormg.xunbo.core.navigation

import com.stormg.xunbo.core.model.Clock
import com.stormg.xunbo.core.model.DangerGuard
import com.stormg.xunbo.core.model.DefaultDangerGuard
import com.stormg.xunbo.core.model.Focus
import com.stormg.xunbo.core.model.FocusNavigator
import com.stormg.xunbo.core.model.KeyExecutor
import com.stormg.xunbo.core.model.MoveOutcome
import com.stormg.xunbo.core.model.NavStepResult
import com.stormg.xunbo.core.model.NormRect
import com.stormg.xunbo.core.model.PerceptionThresholds
import com.stormg.xunbo.core.model.RemoteKey
import com.stormg.xunbo.core.model.ScreenObserver
import com.stormg.xunbo.core.model.ScreenState
import com.stormg.xunbo.core.model.TextMatch
import com.stormg.xunbo.core.model.UiElement
import kotlin.math.abs

class GridFocusNavigator(
    private val executor: KeyExecutor,
    private val clock: Clock,
    private val guard: DangerGuard = DefaultDangerGuard(),
    private val thresholds: PerceptionThresholds = PerceptionThresholds(),
) : FocusNavigator {
    private val visited = mutableSetOf<Pair<String, NormRect?>>()

    override suspend fun moveTo(
        target: UiElement,
        observe: ScreenObserver,
    ): NavStepResult {
        val before = observe.current()
        val focus = before.focus
        if (!trusted(before) || focus?.bounds == null) return result(MoveOutcome.STUCK, null, focus, focus)
        val bounds = checkNotNull(focus.bounds)
        val fresh =
            before.elements.firstOrNull { it.id == target.id && it.bounds == target.bounds && it.text == target.text }
                ?.takeIf { it.confidence >= thresholds.minOcr } ?: return result(MoveOutcome.STUCK, null, focus, focus)
        if (focus.elementId == fresh.id && focus.bounds == fresh.bounds && TextMatch.matches(fresh.text, focus.text)) {
            return result(
                MoveOutcome.ALREADY_THERE,
                null,
                focus,
                focus,
            )
        }
        val dx = fresh.bounds.centerX - bounds.centerX
        val dy = fresh.bounds.centerY - bounds.centerY
        val sameRow = fresh.bounds.top < bounds.bottom && fresh.bounds.bottom > bounds.top
        val key =
            if (sameRow) {
                if (dx > 0) RemoteKey.RIGHT else RemoteKey.LEFT
            } else {
                if (dy > 0) RemoteKey.DOWN else RemoteKey.UP
            }
        val horizontal = key == RemoteKey.LEFT || key == RemoteKey.RIGHT
        val candidates =
            before.elements.filter {
                it.confidence >= thresholds.minOcr && it.id != focus.elementId &&
                    when (key) {
                        RemoteKey.RIGHT -> it.bounds.centerX > bounds.centerX
                        RemoteKey.LEFT -> it.bounds.centerX < bounds.centerX
                        RemoteKey.UP -> it.bounds.centerY < bounds.centerY
                        RemoteKey.DOWN -> it.bounds.centerY > bounds.centerY
                        else -> false
                    }
            }
        val predicted =
            candidates.minWithOrNull(
                compareBy<UiElement> {
                    if (horizontal) abs(it.bounds.centerY - bounds.centerY) else abs(it.bounds.centerX - bounds.centerX)
                }.thenBy {
                    if (horizontal) abs(it.bounds.centerX - bounds.centerX) else abs(it.bounds.centerY - bounds.centerY)
                },
            ) ?: return result(MoveOutcome.STUCK, null, focus, focus)
        visited += focus.text to focus.bounds
        executor.send(key)
        val began = clock.nowMs()
        val after = observe.waitUntilStable()
        val next = after.focus
        val outcome =
            when {
                !trusted(after) || next?.bounds == null -> MoveOutcome.STUCK
                next.text == focus.text && next.bounds == focus.bounds -> MoveOutcome.NO_CHANGE
                (next.text to next.bounds) in visited -> MoveOutcome.WRAPPED
                next.bounds == predicted.bounds && TextMatch.matches(predicted.text, next.text) -> MoveOutcome.STEPPED
                else -> MoveOutcome.OVERSHOOT
            }
        return result(outcome, key, focus, next, clock.nowMs() - began)
    }

    override suspend fun pressOkIfSafe(
        expectedText: String,
        observe: ScreenObserver,
    ): NavStepResult {
        val before = observe.current()
        val focus = before.focus
        if (!trusted(before)) return result(MoveOutcome.STUCK, null, focus, focus)
        if (guard.isDanger(focus!!.text)) return result(MoveOutcome.DANGER_BLOCKED, null, focus, focus)
        if (!TextMatch.matches(expectedText, focus.text)) return result(MoveOutcome.OK_MISMATCH, null, focus, focus)
        executor.send(RemoteKey.OK)
        val start = clock.nowMs()
        val after = observe.waitUntilStable()
        val changed =
            before.fingerprint != after.fingerprint ||
                before.activeTab != after.activeTab ||
                before.selected != after.selected ||
                before.focus != after.focus
        val outcome =
            when {
                !trusted(after) -> MoveOutcome.STUCK
                changed -> MoveOutcome.STEPPED
                else -> MoveOutcome.NO_CHANGE
            }
        return result(outcome, RemoteKey.OK, focus, after.focus, clock.nowMs() - start)
    }

    private fun trusted(screen: ScreenState): Boolean =
        screen.parseConfidence >= thresholds.minParse &&
            screen.focusConfidence >= thresholds.minFocus &&
            (screen.focus?.confidence ?: 0f) >= thresholds.minFocus

    private fun result(
        outcome: MoveOutcome,
        key: RemoteKey?,
        before: Focus?,
        after: Focus?,
        waitedMs: Long = 0,
    ) = NavStepResult(
        outcome,
        key,
        before,
        after,
        waitedMs,
    )
}
