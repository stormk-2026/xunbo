package com.stormg.xunbo.core.decision

import com.stormg.xunbo.core.model.Decider
import com.stormg.xunbo.core.model.DecisionResult
import com.stormg.xunbo.core.model.NextTarget
import com.stormg.xunbo.core.model.NextTargetKind
import com.stormg.xunbo.core.model.PageType
import com.stormg.xunbo.core.model.TextMatch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Deterministic tab-only demo. Never makes a cloud call or claims arbitrary tasks succeeded. */
class RuleStubDecider : Decider {
    override suspend fun decide(stateText: String): DecisionResult {
        val root = Json.parseToJsonElement(stateText).jsonObject
        val task = root.getValue("task").jsonObject
        val screen = root.getValue("screen").jsonObject
        val hint = task["hintTab"]?.jsonPrimitive?.contentOrNull
        val active = screen["activeTab"]?.jsonPrimitive?.contentOrNull
        val focus = (screen["focus"] as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull
        val element =
            screen.getValue("elements").jsonArray.map { it.jsonObject }.firstOrNull {
                hint != null && TextMatch.normalize(it.getValue("text").jsonPrimitive.content) == TextMatch.normalize(hint)
            }
        val arrived = hint != null && active == hint
        val target =
            when {
                arrived -> NextTarget(NextTargetKind.WAIT)
                hint != null && focus == hint -> NextTarget(NextTargetKind.OK_CURRENT)
                element != null -> NextTarget(NextTargetKind.ELEMENT, element.getValue("id").jsonPrimitive.content)
                else -> NextTarget(NextTargetKind.WAIT)
            }
        return DecisionResult(PageType.LAUNCHER, target, if (arrived) 1f else 0f, 0f, 1f, if (hint == null) 0f else 1f, "rule-stub-m0")
    }
}
