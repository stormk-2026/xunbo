package com.stormg.xunbo.core.agent

import com.stormg.xunbo.core.decision.DecisionPolicy
import com.stormg.xunbo.core.decision.JevStateBuilder
import com.stormg.xunbo.core.decision.LocalInstruction
import com.stormg.xunbo.core.model.Clock
import com.stormg.xunbo.core.model.Decider
import com.stormg.xunbo.core.model.DecisionResult
import com.stormg.xunbo.core.model.KeyExecutor
import com.stormg.xunbo.core.model.MoveOutcome
import com.stormg.xunbo.core.model.NavStepResult
import com.stormg.xunbo.core.model.NextTargetKind
import com.stormg.xunbo.core.model.PerceptionThresholds
import com.stormg.xunbo.core.model.PostconditionChecker
import com.stormg.xunbo.core.model.RemoteKey
import com.stormg.xunbo.core.model.ScreenObserver
import com.stormg.xunbo.core.model.ScreenState
import com.stormg.xunbo.core.model.Task
import com.stormg.xunbo.core.model.TaskResult
import com.stormg.xunbo.core.model.TaskRunner
import com.stormg.xunbo.core.model.TaskStatus
import com.stormg.xunbo.core.model.TraceSink
import com.stormg.xunbo.core.model.TraceStep
import com.stormg.xunbo.core.navigation.GridFocusNavigator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicReference

/** Owns one task lifetime. Model decisions never bypass the guarded executor. */
class ExploreController(
    private val decider: Decider,
    private val observer: ScreenObserver,
    private val executor: KeyExecutor,
    private val clock: Clock,
    private val trace: TraceSink,
    private val checker: PostconditionChecker = DefaultPostconditionChecker(),
) : TaskRunner {
    private class Session(val task: Task, val start: Long) {
        lateinit var worker: Deferred<TaskResult>

        @Volatile var stopped = false
        var steps = 0
        var screen: ScreenState? = null
        var decision: DecisionResult? = null
        var pending: TraceStep? = null
        var decideMs = 0L
        val history = mutableListOf<TraceStep>()
    }

    private val active = AtomicReference<Session?>()

    override suspend fun run(task: Task): TaskResult =
        supervisorScope {
            val session = Session(task, clock.nowMs())
            session.worker =
                async(start = CoroutineStart.LAZY) {
                    try {
                        withTimeout(task.limits.maxDurationMs.coerceAtLeast(1)) { explore(session) }
                    } catch (_: TimeoutCancellationException) {
                        result(session, TaskStatus.LIMIT_REACHED, "Task deadline reached")
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        result(session, TaskStatus.FAILED, "Execution failed: ${failure.javaClass.simpleName}")
                    } finally {
                        flush(session, null)
                    }
                }
            if (!active.compareAndSet(null, session)) {
                session.worker.cancel()
                return@supervisorScope TaskResult(TaskStatus.FAILED, 0, 0, "Another task is running")
            }
            try {
                session.worker.await()
            } catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                if (session.stopped) result(session, TaskStatus.STOPPED, "User stopped task") else throw cancelled
            } finally {
                session.worker.cancel()
                executor.cancelPending()
                active.compareAndSet(session, null)
            }
        }

    override fun stop() {
        active.get()?.let {
            it.stopped = true
            it.worker.cancel()
            executor.cancelPending()
        }
    }

    private suspend fun explore(s: Session): TaskResult {
        val policy = DecisionPolicy()
        val taskObserver =
            object : ScreenObserver {
                override suspend fun current(): ScreenState = observer.current().also { s.screen = it }

                override suspend fun waitUntilStable(): ScreenState = observer.waitUntilStable().also { s.screen = it }
            }
        val guarded =
            object : KeyExecutor {
                override suspend fun send(key: RemoteKey) {
                    currentCoroutineContext().ensureActive()
                    check(!s.stopped && active.get() === s) { "Inactive task" }
                    if (s.steps >= s.task.limits.maxSteps || expired(s)) throw BudgetReached()
                    check(key !in setOf(RemoteKey.POWER, RemoteKey.VOL_UP, RemoteKey.VOL_DOWN)) { "Forbidden automatic key" }
                    val before = checkNotNull(s.screen)
                    s.steps++
                    s.pending =
                        TraceStep(
                            s.history.size, before.capturedAtMs, before, s.decision, key, null,
                            decideMs = s.decideMs,
                            note = "Dispatch attempted; result not yet observed",
                        )
                    val begin = clock.nowMs()
                    executor.send(key)
                    s.pending = s.pending?.copy(irMs = clock.nowMs() - begin, note = "Sent; observation pending")
                }

                override fun cancelPending() = executor.cancelPending()
            }
        val navigator = GridFocusNavigator(guarded, clock)
        var unchanged = 0
        var retry: DecisionResult? = null
        var screen: ScreenState
        if (s.task.limits.maxDurationMs <= 0 || s.task.limits.maxSteps < 0) {
            return result(
                s,
                TaskStatus.LIMIT_REACHED,
                "Invalid task limits",
            )
        }
        screen = taskObserver.waitUntilStable()
        while (true) {
            currentCoroutineContext().ensureActive()
            if (expired(s)) return result(s, TaskStatus.LIMIT_REACHED, "Task deadline reached")
            if (checker.satisfied(s.task, screen)) return result(s, TaskStatus.SUCCEEDED)
            if (s.steps >= s.task.limits.maxSteps) return result(s, TaskStatus.LIMIT_REACHED, "Key budget reached")
            val begin = clock.nowMs()
            val decision = retry ?: decider.decide(JevStateBuilder().build(s.task, screen, s.history))
            s.decideMs = clock.nowMs() - begin
            s.decision = decision
            currentCoroutineContext().ensureActive()
            if (expired(s)) return result(s, TaskStatus.LIMIT_REACHED, "Task deadline reached")
            val instruction = policy.evaluate(decision, screen, s.task)
            val nav =
                try {
                    when (instruction) {
                        LocalInstruction.Stop -> return result(s, TaskStatus.STUCK, "Decision or perception is uncertain")
                        LocalInstruction.CheckPostcondition -> return result(s, TaskStatus.STUCK, "Arrival candidate failed postconditions")
                        LocalInstruction.PressBack -> press(RemoteKey.BACK, guarded, taskObserver)
                        LocalInstruction.PressHome -> press(RemoteKey.HOME, guarded, taskObserver)
                        LocalInstruction.Proceed ->
                            when (decision.nextTarget.kind) {
                                NextTargetKind.ELEMENT -> {
                                    val target =
                                        screen.elements.firstOrNull { it.id == decision.nextTarget.elementId }
                                            ?: return result(s, TaskStatus.STUCK, "Target missing from current frame")
                                    navigator.moveTo(target, taskObserver)
                                }
                                NextTargetKind.OK_CURRENT -> {
                                    val expected =
                                        s.task.intent.targetValue ?: s.task.intent.title ?: s.task.intent.hintTab
                                            ?: return result(s, TaskStatus.STUCK, "No verified OK target")
                                    navigator.pressOkIfSafe(expected, taskObserver)
                                }
                                NextTargetKind.WAIT -> {
                                    clock.sleep(minOf(100, (s.task.limits.maxDurationMs - (clock.nowMs() - s.start)).coerceAtLeast(1)))
                                    screen = taskObserver.waitUntilStable()
                                    continue
                                }
                                else -> return result(s, TaskStatus.STUCK, "Action is outside the M0 demo")
                            }
                    }
                } catch (_: BudgetReached) {
                    return result(s, TaskStatus.LIMIT_REACHED, "Key or time budget reached")
                }
            flush(s, nav)
            when (nav.outcome) {
                MoveOutcome.NO_CHANGE -> {
                    unchanged++
                    if (unchanged >= 2) return result(s, TaskStatus.STUCK, "No change after one retry")
                    retry = decision
                }
                MoveOutcome.STEPPED -> {
                    unchanged = 0
                    retry = null
                }
                else -> return result(s, TaskStatus.STUCK, "Navigation: ${nav.outcome}")
            }
            screen = checkNotNull(s.screen)
        }
    }

    private suspend fun press(
        key: RemoteKey,
        executor: KeyExecutor,
        observer: ScreenObserver,
    ): NavStepResult {
        val before = observer.current()
        executor.send(key)
        val began = clock.nowMs()
        val after = observer.waitUntilStable()
        val changed = before.fingerprint != after.fingerprint || before.focus != after.focus || before.selected != after.selected
        val outcome =
            when {
                !(after.parseConfidence >= PerceptionThresholds().minParse) -> MoveOutcome.STUCK
                changed -> MoveOutcome.STEPPED
                else -> MoveOutcome.NO_CHANGE
            }
        return NavStepResult(outcome, key, before.focus, after.focus, clock.nowMs() - began)
    }

    private fun flush(
        s: Session,
        nav: NavStepResult?,
    ) {
        val pending = s.pending ?: return
        val step = pending.copy(nav = nav, waitMs = nav?.waitedMs ?: 0, note = if (nav != null) null else pending.note)
        trace.append(step)
        s.history += step
        s.pending = null
    }

    private fun expired(s: Session) = clock.nowMs() - s.start >= s.task.limits.maxDurationMs

    private fun result(
        s: Session,
        status: TaskStatus,
        reason: String? = null,
    ) = TaskResult(
        status,
        s.steps,
        clock.nowMs() - s.start,
        reason,
    )

    private class BudgetReached : RuntimeException()
}
