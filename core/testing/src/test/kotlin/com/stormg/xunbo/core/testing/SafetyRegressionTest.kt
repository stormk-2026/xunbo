package com.stormg.xunbo.core.testing

import com.stormg.xunbo.core.agent.DefaultPostconditionChecker
import com.stormg.xunbo.core.agent.ExploreController
import com.stormg.xunbo.core.agent.InMemoryTraceSink
import com.stormg.xunbo.core.agent.RuleIntentParser
import com.stormg.xunbo.core.decision.JevStateBuilder
import com.stormg.xunbo.core.decision.RuleStubDecider
import com.stormg.xunbo.core.model.Clock
import com.stormg.xunbo.core.model.Decider
import com.stormg.xunbo.core.model.DecisionResult
import com.stormg.xunbo.core.model.Focus
import com.stormg.xunbo.core.model.KeyExecutor
import com.stormg.xunbo.core.model.MoveOutcome
import com.stormg.xunbo.core.model.NextTarget
import com.stormg.xunbo.core.model.NextTargetKind
import com.stormg.xunbo.core.model.NormRect
import com.stormg.xunbo.core.model.PageType
import com.stormg.xunbo.core.model.RemoteKey
import com.stormg.xunbo.core.model.ScreenObserver
import com.stormg.xunbo.core.model.ScreenState
import com.stormg.xunbo.core.model.TaskLimits
import com.stormg.xunbo.core.model.TaskStatus
import com.stormg.xunbo.core.model.UiElement
import com.stormg.xunbo.core.navigation.GridFocusNavigator
import com.stormg.xunbo.core.navigation.SerialKeyExecutor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SafetyRegressionTest {
    private val task = RuleIntentParser().parse("进入教育")

    @Test fun requestedEpisodeCannotSucceedOnAnotherEpisode() =
        runTest {
            val tv = FakeTv(FakeClock())
            val screen = tv.parse(tv.latest()).copy(pageType = PageType.PLAYER, elements = listOf(element(0, "小猪佩奇"), element(1, "第123集")))
            val play = RuleIntentParser().parse("在教育分类下找小猪佩奇第23集")
            val checker = DefaultPostconditionChecker()
            assertFalse(checker.satisfied(play, screen))
            assertTrue(checker.satisfied(play, screen.copy(elements = listOf(element(0, "小猪佩奇"), element(1, "23集")))))
        }

    @Test fun hangingInitialObservationRespectsDeadline() =
        runTest {
            val clock = SchedulerClock(testScheduler)
            val tv = FakeTv(clock)
            val observe =
                object : ScreenObserver {
                    override suspend fun current(): ScreenState = awaitCancellation()

                    override suspend fun waitUntilStable(): ScreenState = awaitCancellation()
                }
            val runner = ExploreController(RuleStubDecider(), observe, SerialKeyExecutor(tv, FakeTv.codes), clock, InMemoryTraceSink())
            val result = runner.run(task.copy(limits = TaskLimits(maxDurationMs = 100)))
            assertEquals(TaskStatus.LIMIT_REACHED, result.status)
            assertEquals(100L, result.elapsedMs)
            assertTrue(tv.sentKeys.isEmpty())
        }

    @Test fun hangingPostKeyObservationStillRecordsTheSentKey() =
        runTest {
            val clock = SchedulerClock(testScheduler)
            val tv = FakeTv(clock)
            var observations = 0
            val observe =
                object : ScreenObserver {
                    override suspend fun current() = tv.parse(tv.latest())

                    override suspend fun waitUntilStable(): ScreenState {
                        observations++
                        if (observations > 1) awaitCancellation()
                        return current()
                    }
                }
            val trace = InMemoryTraceSink()
            val result =
                ExploreController(
                    RuleStubDecider(),
                    observe,
                    SerialKeyExecutor(tv, FakeTv.codes),
                    clock,
                    trace,
                ).run(task.copy(limits = TaskLimits(maxDurationMs = 100)))
            assertEquals(TaskStatus.LIMIT_REACHED, result.status)
            assertEquals(listOf(RemoteKey.UP), tv.sentKeys)
            assertEquals(tv.sentKeys, trace.all().mapNotNull { it.key })
            assertEquals(1, result.steps)
        }

    @Test fun lateDecisionAfterStopCannotSendAndNextTaskCanRun() =
        runTest {
            val clock = SchedulerClock(testScheduler)
            val tv = FakeTv(clock)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var first = true
            val decider =
                object : Decider {
                    override suspend fun decide(stateText: String): DecisionResult {
                        if (first) {
                            first = false
                            entered.complete(Unit)
                            withContext(NonCancellable) { release.await() }
                        }
                        return RuleStubDecider().decide(stateText)
                    }
                }
            val runner =
                ExploreController(decider, FakeScreenObserver(tv, clock), SerialKeyExecutor(tv, FakeTv.codes), clock, InMemoryTraceSink())
            val job = async { runner.run(task) }
            entered.await()
            assertEquals(TaskStatus.FAILED, runner.run(task).status)
            runner.stop()
            release.complete(Unit)
            assertEquals(TaskStatus.STOPPED, job.await().status)
            assertTrue(tv.sentKeys.isEmpty())
            assertEquals(TaskStatus.SUCCEEDED, runner.run(task).status)
            assertEquals(5, tv.sentKeys.size)
        }

    @Test fun falseArrivalOrBrokenDecisionNeverProducesSuccessOrKeys() =
        runTest {
            val clock = FakeClock()
            val tv = FakeTv(clock)
            val decider =
                object : Decider {
                    override suspend fun decide(stateText: String) =
                        DecisionResult(PageType.LAUNCHER, NextTarget(NextTargetKind.WAIT), 1f, 0f, 1f, 1f)
                }
            val runner =
                ExploreController(decider, FakeScreenObserver(tv, clock), SerialKeyExecutor(tv, FakeTv.codes), clock, InMemoryTraceSink())
            assertEquals(TaskStatus.STUCK, runner.run(task).status)
            assertTrue(tv.sentKeys.isEmpty())
            val broken =
                object : Decider {
                    override suspend fun decide(stateText: String): DecisionResult = error("Invalid response")
                }
            val failed =
                ExploreController(broken, FakeScreenObserver(tv, clock), SerialKeyExecutor(tv, FakeTv.codes), clock, InMemoryTraceSink())
            assertEquals(TaskStatus.FAILED, failed.run(task).status)
            assertTrue(tv.sentKeys.isEmpty())
        }

    @Test fun lowConfidenceFocusAndTargetCannotSend() =
        runTest {
            val clock = FakeClock()
            val tv = FakeTv(clock)
            val state = tv.parse(tv.latest())
            val executor = SerialKeyExecutor(tv, FakeTv.codes)
            val target = state.elements.first { it.text == "教育" }
            val nav = GridFocusNavigator(executor, clock)
            val lowFocus = fixed(state.copy(focus = state.focus!!.copy(confidence = .1f)))
            assertEquals(MoveOutcome.STUCK, nav.moveTo(target, lowFocus).outcome)
            val lowTarget = target.copy(confidence = .1f)
            val observe = fixed(state.copy(elements = state.elements.map { if (it.id == target.id) lowTarget else it }))
            assertEquals(MoveOutcome.STUCK, nav.moveTo(lowTarget, observe).outcome)
            assertTrue(tv.sentKeys.isEmpty())
        }

    @Test fun equalTextAtDifferentPositionsIsNotWrapAndFingerprintMayStaySame() =
        runTest {
            val clock = FakeClock()
            val scene = FakeScene(listOf(FakeCell("同名", 0, 0), FakeCell("同名", 0, 1), FakeCell("终点", 0, 2)), emptyList(), 0, null)
            val tv = FakeTv(clock, scene)
            val observe = FakeScreenObserver(tv, clock)
            val initial = observe.current()
            val nav = GridFocusNavigator(SerialKeyExecutor(tv, FakeTv.codes), clock)
            assertEquals(MoveOutcome.STEPPED, nav.moveTo(initial.elements.last(), observe).outcome)
            assertEquals(initial.fingerprint, observe.current().fingerprint)
            assertEquals(MoveOutcome.STEPPED, nav.moveTo(observe.current().elements.last(), observe).outcome)
        }

    @Test fun jumpOverOneCellIsOvershoot() =
        runTest {
            val clock = FakeClock()
            val elements = (0..2).map { element(it, "卡片$it") }
            var state =
                ScreenState(
                    PageType.TAB_BAR,
                    focus = focus(elements[0]),
                    elements = elements,
                    fingerprint = "same",
                    capturedAtMs = 0,
                    parseConfidence = 1f,
                )
            val observe =
                object : ScreenObserver {
                    override suspend fun current() = state

                    override suspend fun waitUntilStable() = state
                }
            val executor =
                object : KeyExecutor {
                    override suspend fun send(key: RemoteKey) {
                        state = state.copy(focus = focus(elements[2]))
                    }

                    override fun cancelPending() = Unit
                }
            assertEquals(MoveOutcome.OVERSHOOT, GridFocusNavigator(executor, clock).moveTo(elements[2], observe).outcome)
        }

    @Test fun lowConfidenceHistoryMustNotBePresentedAsFact() =
        runTest {
            val clock = FakeClock()
            val tv = FakeTv(clock)
            val screen = tv.parse(tv.latest())
            val bad = screen.copy(parseConfidence = .1f, activeTab = "UNTRUSTED_TAB")
            val history = listOf(com.stormg.xunbo.core.model.TraceStep(0, 0, bad, null, null, null))
            val stateText = JevStateBuilder().build(task, screen, history)
            assertFalse(stateText.contains("UNTRUSTED_TAB"))
        }

    private fun element(
        index: Int,
        text: String,
    ) = UiElement(
        "e$index",
        text,
        NormRect(index * .3f, 0f, index * .3f + .2f, .2f),
        confidence = 1f,
    )

    private fun focus(element: UiElement) = Focus(element.id, element.text, element.bounds, 1f)

    private fun fixed(screen: ScreenState) =
        object : ScreenObserver {
            override suspend fun current() = screen

            override suspend fun waitUntilStable() = screen
        }

    private class SchedulerClock(private val scheduler: TestCoroutineScheduler) : Clock {
        override fun nowMs() = scheduler.currentTime

        override suspend fun sleep(ms: Long) = delay(ms)
    }
}
