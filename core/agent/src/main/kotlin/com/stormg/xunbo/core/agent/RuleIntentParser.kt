package com.stormg.xunbo.core.agent

import com.stormg.xunbo.core.model.IntentParser
import com.stormg.xunbo.core.model.PageType
import com.stormg.xunbo.core.model.Postcondition
import com.stormg.xunbo.core.model.Task
import com.stormg.xunbo.core.model.TaskAction
import com.stormg.xunbo.core.model.TaskIntent
import java.util.UUID

class RuleIntentParser : IntentParser {
    override fun parse(raw: String): Task {
        val hint = "教育".takeIf { raw.contains(it) }
        val title = "小猪佩奇".takeIf { raw.contains(it) }
        val episode = Regex("第([0-9]+)集").find(raw)?.groupValues?.get(1)?.toIntOrNull()
        val action = if (title != null) TaskAction.PLAY else TaskAction.OPEN
        val intent = TaskIntent(raw, title, episode, hint, action = action)
        val postconditions =
            when {
                title != null ->
                    buildList {
                        add(Postcondition.PageIs(PageType.PLAYER))
                        add(Postcondition.TextVisible(title))
                        episode?.let { add(Postcondition.TextVisible("第${it}集")) }
                    }
                hint != null -> listOf(Postcondition.ActiveTab(hint))
                else -> listOf(Postcondition.FocusOn(raw))
            }
        return Task(UUID.randomUUID().toString(), intent, postconditions)
    }
}
