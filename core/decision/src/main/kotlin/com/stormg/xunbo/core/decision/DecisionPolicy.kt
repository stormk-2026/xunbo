package com.stormg.xunbo.core.decision

import com.stormg.xunbo.core.model.DecisionResult
import com.stormg.xunbo.core.model.DecisionThresholds
import com.stormg.xunbo.core.model.NextTargetKind
import com.stormg.xunbo.core.model.PageType
import com.stormg.xunbo.core.model.PerceptionThresholds
import com.stormg.xunbo.core.model.ScreenState
import com.stormg.xunbo.core.model.Task

enum class LocalInstruction { Proceed, PressBack, PressHome, Stop, CheckPostcondition }

class DecisionPolicy(
    private val thresholds: DecisionThresholds = DecisionThresholds(),
    private val perception: PerceptionThresholds = PerceptionThresholds(),
) {
    private var offPathCount = 0

    fun reset() {
        offPathCount = 0
    }

    fun evaluate(
        decision: DecisionResult,
        screen: ScreenState,
        task: Task,
    ): LocalInstruction {
        val values = listOf(decision.confidence, decision.arrived, decision.popup, decision.onPath)
        if (values.any { !it.isFinite() || it !in 0f..1f } || !(screen.parseConfidence >= perception.minParse)) return LocalInstruction.Stop
        if (task.postconditions.isEmpty() || decision.confidence < thresholds.minConfidence) return LocalInstruction.Stop
        if (decision.popup > thresholds.popup || screen.pageType == PageType.POPUP) return LocalInstruction.PressBack
        offPathCount = if (decision.onPath < thresholds.onPathLow) offPathCount + 1 else 0
        if (offPathCount >= 2) return LocalInstruction.PressBack
        if (decision.arrived > thresholds.arrived) return LocalInstruction.CheckPostcondition
        return when (decision.nextTarget.kind) {
            NextTargetKind.BACK -> LocalInstruction.PressBack
            NextTargetKind.HOME -> LocalInstruction.PressHome
            else -> LocalInstruction.Proceed
        }
    }
}
