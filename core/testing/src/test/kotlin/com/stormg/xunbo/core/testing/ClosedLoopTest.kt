package com.stormg.xunbo.core.testing

import com.stormg.xunbo.core.agent.DefaultPostconditionChecker
import com.stormg.xunbo.core.agent.ExploreController
import com.stormg.xunbo.core.agent.InMemoryTraceSink
import com.stormg.xunbo.core.agent.RuleIntentParser
import com.stormg.xunbo.core.decision.DecisionPolicy
import com.stormg.xunbo.core.decision.JevStateBuilder
import com.stormg.xunbo.core.decision.LocalInstruction
import com.stormg.xunbo.core.decision.RuleStubDecider
import com.stormg.xunbo.core.model.Clock
import com.stormg.xunbo.core.model.Decider
import com.stormg.xunbo.core.model.DecisionResult
import com.stormg.xunbo.core.model.Focus
import com.stormg.xunbo.core.model.IrCode
import com.stormg.xunbo.core.model.IrTransmitter
import com.stormg.xunbo.core.model.MoveOutcome
import com.stormg.xunbo.core.model.NextTarget
import com.stormg.xunbo.core.model.NextTargetKind
import com.stormg.xunbo.core.model.NormRect
import com.stormg.xunbo.core.model.PageType
import com.stormg.xunbo.core.model.Postcondition
import com.stormg.xunbo.core.model.RemoteKey
import com.stormg.xunbo.core.model.ScreenObserver
import com.stormg.xunbo.core.model.Selection
import com.stormg.xunbo.core.model.SelectionKind
import com.stormg.xunbo.core.model.TaskAction
import com.stormg.xunbo.core.model.TaskLimits
import com.stormg.xunbo.core.model.TaskStatus
import com.stormg.xunbo.core.model.TraceStep
import com.stormg.xunbo.core.model.UiElement
import com.stormg.xunbo.core.navigation.GridFocusNavigator
import com.stormg.xunbo.core.navigation.SerialKeyExecutor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ClosedLoopTest {
    private fun rig(
        clock: Clock = FakeClock(),
        decider: Decider = RuleStubDecider(),
    ): Rig = Rig(clock, decider)

    @Test fun exploreEducationTabFromContentCard() =
        runTest {
            val r = rig()
            val result = r.runner.run(r.task)
            assertEquals(TaskStatus.SUCCEEDED, result.status)
            assertEquals("教育", r.observer.current().activeTab)
            assertEquals(listOf(RemoteKey.UP, RemoteKey.RIGHT, RemoteKey.RIGHT, RemoteKey.RIGHT, RemoteKey.OK), r.tv.sentKeys)
            assertEquals(r.tv.sentKeys, r.trace.all().mapNotNull { it.key })
            assertEquals(5, result.steps)
            assertTrue(result.steps <= 20)
            assertFalse(r.tv.sentKeys.contains(RemoteKey.POWER))
            assertTrue(r.trace.all().filter { it.key != null }.all { it.nav != null })
        }

    @Test fun parserExtractsTitleEpisodeAndTab() {
        val task = RuleIntentParser().parse("在教育分类下找小猪佩奇第23集")
        assertEquals("小猪佩奇", task.intent.title)
        assertEquals(23, task.intent.episode)
        assertEquals("教育", task.intent.hintTab)
        assertEquals(TaskAction.PLAY, task.intent.action)
    }

    @Test fun postconditionsNeverConfuseFocusWithSelectionOrActivation() =
        runTest {
            val r = rig()
            val checker = DefaultPostconditionChecker()
            val screen = r.observer.current()
            assertFalse(checker.satisfied(r.task, screen))
            val focus = Focus("e5", "教育", NormRect(.5f, 0f, .6f, .1f), 1f)
            assertFalse(checker.satisfied(r.task, screen.copy(focus = focus)))
            assertTrue(checker.satisfied(r.task, screen.copy(activeTab = "教育")))
            assertFalse(checker.satisfied(r.task, screen.copy(activeTab = "教育", parseConfidence = .1f)))
            assertFalse(checker.satisfied(r.task.copy(postconditions = emptyList()), screen))
            val setting = r.task.copy(postconditions = listOf(Postcondition.Selected("1080P_50Hz", true)))
            assertFalse(checker.satisfied(setting, screen.copy(focus = focus.copy(text = "1080P_50Hz"))))
            val selection = Selection(null, "1080P_50Hz", SelectionKind.RADIO, true, 1f)
            assertTrue(checker.satisfied(setting, screen.copy(selected = listOf(selection))))
            assertFalse(checker.satisfied(setting, screen.copy(selected = listOf(selection.copy(confidence = .1f)))))
        }

    @Test fun builderLimitsElementsAndHistoryAndRetainsFocus() =
        runTest {
            val r = rig()
            val initial = r.observer.current()
            val elements = (0..49).map { UiElement("e$it", "卡片$it", NormRect(0f, 0f, .1f, .1f), confidence = 1f) }
            val screen = initial.copy(elements = elements, focus = Focus("e49", "卡片49", elements.last().bounds, 1f))
            val steps = (0..11).map { TraceStep(it, 0, screen, null, RemoteKey.RIGHT, null) }
            val text = JevStateBuilder().build(r.task, screen, steps)
            val json = Json.parseToJsonElement(text).jsonObject
            assertEquals(setOf("task", "policy", "recentSteps", "screen"), json.keys)
            assertEquals("教育", json.getValue("task").jsonObject.getValue("hintTab").jsonPrimitive.content)
            assertEquals(8, json.getValue("recentSteps").jsonArray.size)
            val state = json.getValue("screen").jsonObject
            assertEquals(30, state.getValue("elements").jsonArray.size)
            assertTrue(state.getValue("elements").jsonArray.any { it.jsonObject["id"]?.jsonPrimitive?.content == "e49" })
        }

    @Test fun policyIsConservativeAndResetsBetweenTasks() =
        runTest {
            val r = rig()
            val policy = DecisionPolicy()
            val screen = r.observer.current()
            val good = decision(NextTargetKind.WAIT)
            assertEquals(LocalInstruction.PressBack, policy.evaluate(good.copy(popup = .9f), screen, r.task))
            assertEquals(LocalInstruction.Stop, policy.evaluate(good.copy(confidence = .1f), screen, r.task))
            assertEquals(LocalInstruction.CheckPostcondition, policy.evaluate(good.copy(arrived = 1f), screen, r.task))
            assertEquals(LocalInstruction.Stop, policy.evaluate(good.copy(confidence = Float.NaN), screen, r.task))
            policy.reset()
            policy.evaluate(good.copy(onPath = .1f), screen, r.task)
            assertEquals(LocalInstruction.PressBack, policy.evaluate(good.copy(onPath = .1f), screen, r.task))
            policy.reset()
            assertEquals(LocalInstruction.Proceed, policy.evaluate(good.copy(onPath = .1f), screen, r.task))
        }

    @Test fun noChangeRetriesOnlyOnceAndCountsEveryKey() =
        runTest {
            val r = rig()
            r.tv.dropKeys = true
            val result = r.runner.run(r.task)
            assertEquals(TaskStatus.STUCK, result.status)
            assertEquals(2, result.steps)
            assertEquals(listOf(RemoteKey.UP, RemoteKey.UP), r.tv.sentKeys)
            assertEquals(2, r.trace.all().count { it.key != null })
        }

    @Test fun stepBudgetPreventsHiddenRetry() =
        runTest {
            val r = rig()
            r.tv.dropKeys = true
            val result = r.runner.run(r.task.copy(limits = TaskLimits(maxSteps = 1)))
            assertEquals(TaskStatus.LIMIT_REACHED, result.status)
            assertEquals(1, r.tv.sentKeys.size)
        }

    @Test fun stopCancelsHangingDecisionAndLateResponseCannotSend() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val r =
                rig(
                    SchedulerClock(testScheduler),
                    object : Decider {
                        override suspend fun decide(stateText: String): DecisionResult {
                            entered.complete(Unit)
                            awaitCancellation()
                        }
                    },
                )
            val job = async { r.runner.run(r.task) }
            entered.await()
            r.runner.stop()
            assertEquals(TaskStatus.STOPPED, job.await().status)
            assertTrue(r.tv.sentKeys.isEmpty())
        }

    @Test fun hangingDecisionAndWaitBothRespectDeadline() =
        runTest {
            val hanging =
                rig(
                    SchedulerClock(testScheduler),
                    object : Decider {
                        override suspend fun decide(stateText: String): DecisionResult = awaitCancellation()
                    },
                )
            assertEquals(TaskStatus.LIMIT_REACHED, hanging.runner.run(hanging.task.copy(limits = TaskLimits(maxDurationMs = 100))).status)
            val waiting =
                rig(
                    decider =
                        object : Decider {
                            override suspend fun decide(stateText: String) = decision(NextTargetKind.WAIT)
                        },
                )
            assertEquals(TaskStatus.LIMIT_REACHED, waiting.runner.run(waiting.task.copy(limits = TaskLimits(maxDurationMs = 300))).status)
            assertTrue(waiting.tv.sentKeys.isEmpty())
        }

    @Test fun okRequiresKnownSafeMatchingFocus() =
        runTest {
            val r = rig()
            val screen = r.observer.current()
            var observed = screen
            val observer =
                object : ScreenObserver {
                    override suspend fun current() = observed

                    override suspend fun waitUntilStable() = observed
                }
            val nav = GridFocusNavigator(r.executor, r.clock)
            assertEquals(MoveOutcome.OK_MISMATCH, nav.pressOkIfSafe("教育", observer).outcome)
            observed = screen.copy(focus = screen.focus!!.copy(text = "购买教育"))
            assertEquals(MoveOutcome.DANGER_BLOCKED, nav.pressOkIfSafe("教育", observer).outcome)
            observed = screen.copy(parseConfidence = .1f)
            assertEquals(MoveOutcome.STUCK, nav.pressOkIfSafe("沙漠往事", observer).outcome)
            assertTrue(r.tv.sentKeys.isEmpty())
        }

    @Test fun queueIsSerialAndCancelDiscardsWaiters() =
        runTest {
            val began = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val sent = mutableListOf<RemoteKey>()
            val transmitter =
                object : IrTransmitter {
                    override val isReady = true

                    override suspend fun send(code: IrCode) {
                        sent += code.name
                        began.complete(Unit)
                        release.await()
                    }
                }
            val queue = SerialKeyExecutor(transmitter, FakeTv.codes)
            val first = launch { queue.send(RemoteKey.UP) }
            began.await()
            val next = launch { queue.send(RemoteKey.RIGHT) }
            runCurrent()
            queue.cancelPending()
            release.complete(Unit)
            joinAll(first, next)
            assertEquals(listOf(RemoteKey.UP), sent)
        }

    private fun decision(kind: NextTargetKind) = DecisionResult(PageType.LAUNCHER, NextTarget(kind), 0f, 0f, 1f, 1f)

    private class SchedulerClock(val scheduler: TestCoroutineScheduler) : Clock {
        override fun nowMs() = scheduler.currentTime

        override suspend fun sleep(ms: Long) = delay(ms)
    }

    private class Rig(val clock: Clock, decider: Decider) {
        val tv = FakeTv(clock)
        val observer = FakeScreenObserver(tv, clock)
        val executor = SerialKeyExecutor(tv, FakeTv.codes)
        val trace = InMemoryTraceSink()
        val runner = ExploreController(decider, observer, executor, clock, trace)
        val task = RuleIntentParser().parse("进入教育")
    }
}
