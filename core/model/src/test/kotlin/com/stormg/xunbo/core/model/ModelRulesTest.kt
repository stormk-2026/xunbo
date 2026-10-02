package com.stormg.xunbo.core.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRulesTest {
    @Test fun textMatchingRejectsAmbiguity() {
        assertTrue(TextMatch.matches(" 教 育！", "教育"))
        assertTrue(TextMatch.matches("ABC", "ａｂｃ"))
        assertTrue(TextMatch.matches("小猪佩奇", "小猪佩奇第23集"))
        assertTrue(TextMatch.matches("上", "上"))
        assertFalse(TextMatch.matches("教", "教育"))
        assertFalse(TextMatch.matches("", ""))
        assertFalse(TextMatch.matches("23", "第123集"))
        assertFalse(TextMatch.matches("23", "第23集"))
        assertTrue(TextMatch.matchesEpisode(23, "第２３集"))
        assertTrue(TextMatch.matchesEpisode(23, "23"))
        assertTrue(TextMatch.matchesEpisode(23, "23集"))
        assertFalse(TextMatch.matchesEpisode(23, "第123集"))
        assertFalse(TextMatch.matchesEpisode(23, "标题23"))
    }

    @Test fun dangerWordsAndPricesAreBlocked() {
        val guard = DefaultDangerGuard()
        listOf("重启", "恢复出厂", "购买", "9.9元", "¥12", "￥１２", "会员", "元/月", "卸载").forEach {
            assertTrue(it, guard.isDanger(it))
        }
        listOf("教育", "小猪佩奇").forEach { assertFalse(it, guard.isDanger(it)) }
    }

    @Test fun importedKeyAliasesAndGoalKeysAreStable() {
        val parser = IrKeyNameParser()
        assertEquals(RemoteKey.OK, parser.parse("确定"))
        assertEquals(RemoteKey.BACK, parser.parse("返回"))
        assertEquals(RemoteKey.MENU, parser.parse("设置"))
        assertEquals(RemoteKey.UP, parser.parse("UP"))
        assertEquals(RemoteKey.VOL_UP, parser.parse("音量+"))
        assertNull(parser.parse("unknown"))
        assertEquals("play:小猪佩奇|ep=23|tab=教育", GoalKeys.of(TaskIntent("raw", "小猪佩奇", 23, "教育", action = TaskAction.PLAY)))
    }

    @Test fun contractsRoundTripWithoutLosingFields() {
        val screen = ScreenState(PageType.TAB_BAR, fingerprint = "page", capturedAtMs = 42, parseConfidence = 1f)
        val decision = DecisionResult(PageType.TAB_BAR, NextTarget(NextTargetKind.WAIT), 0f, 0f, 1f, 1f)
        val trace = TraceStep(0, 42, screen, decision, null, null)
        val skill = Skill("device", "open:教育", steps = listOf(SkillStep(RemoteKey.OK, "教育")), createdAtMs = 42)
        assertEquals(screen, Json.decodeFromString<ScreenState>(Json.encodeToString(screen)))
        assertEquals(decision, Json.decodeFromString<DecisionResult>(Json.encodeToString(decision)))
        assertEquals(trace, Json.decodeFromString<TraceStep>(Json.encodeToString(trace)))
        assertEquals(skill, Json.decodeFromString<Skill>(Json.encodeToString(skill)))
        val post: Postcondition = Postcondition.ActiveTab("教育")
        assertEquals(post, Json.decodeFromString<Postcondition>(Json.encodeToString(post)))
    }
}
