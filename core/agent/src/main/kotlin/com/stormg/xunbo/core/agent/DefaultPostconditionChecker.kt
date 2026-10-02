package com.stormg.xunbo.core.agent

import com.stormg.xunbo.core.model.PerceptionThresholds
import com.stormg.xunbo.core.model.Postcondition
import com.stormg.xunbo.core.model.PostconditionChecker
import com.stormg.xunbo.core.model.ScreenState
import com.stormg.xunbo.core.model.Task
import com.stormg.xunbo.core.model.TextMatch

class DefaultPostconditionChecker(private val thresholds: PerceptionThresholds = PerceptionThresholds()) : PostconditionChecker {
    override fun satisfied(
        task: Task,
        screen: ScreenState,
    ): Boolean {
        if (!(screen.parseConfidence >= thresholds.minParse) || task.postconditions.isEmpty()) return false
        return task.postconditions.all { post ->
            when (post) {
                is Postcondition.ActiveTab ->
                    !screen.activeTab.isNullOrBlank() &&
                        TextMatch.normalize(post.text).isNotEmpty() &&
                        TextMatch.normalize(post.text) == TextMatch.normalize(screen.activeTab.orEmpty())
                is Postcondition.FocusOn ->
                    screen.focus?.let {
                        it.confidence >= thresholds.minFocus &&
                            screen.focusConfidence >= thresholds.minFocus &&
                            TextMatch.matches(post.text, it.text)
                    } ?: false
                is Postcondition.TextVisible ->
                    screen.elements.any {
                        val episode = task.intent.episode
                        val matches =
                            if (episode != null && TextMatch.matchesEpisode(episode, post.text)) {
                                TextMatch.matchesEpisode(episode, it.text)
                            } else {
                                TextMatch.matches(post.text, it.text)
                            }
                        it.confidence >= thresholds.minOcr && matches
                    }
                is Postcondition.Selected ->
                    screen.selected.any {
                        it.confidence >= thresholds.minSelection && it.on == post.on && TextMatch.matches(post.text, it.text)
                    }
                is Postcondition.PageIs -> screen.pageType == post.type
            }
        }
    }
}
