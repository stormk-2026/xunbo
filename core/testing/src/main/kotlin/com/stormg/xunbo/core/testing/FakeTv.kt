package com.stormg.xunbo.core.testing

import com.stormg.xunbo.core.model.Clock
import com.stormg.xunbo.core.model.Focus
import com.stormg.xunbo.core.model.Frame
import com.stormg.xunbo.core.model.FrameSource
import com.stormg.xunbo.core.model.IrCode
import com.stormg.xunbo.core.model.IrTransmitter
import com.stormg.xunbo.core.model.NormRect
import com.stormg.xunbo.core.model.PageType
import com.stormg.xunbo.core.model.Perception
import com.stormg.xunbo.core.model.RemoteKey
import com.stormg.xunbo.core.model.ScreenObserver
import com.stormg.xunbo.core.model.ScreenState
import com.stormg.xunbo.core.model.Selection
import com.stormg.xunbo.core.model.SelectionKind
import com.stormg.xunbo.core.model.UiElement
import java.security.MessageDigest
import kotlin.math.abs

/** A programmable sparse grid. All codes are synthetic and must never be used on hardware. */
data class FakeCell(val text: String, val row: Int, val col: Int, val selected: Boolean? = null, val danger: Boolean = false)

data class FakeScene(
    val cells: List<FakeCell>,
    val tabs: List<String>,
    val initialFocus: Int,
    val activeTab: String?,
    val wrap: Boolean = false,
)

object DangbeiHome {
    val tabs = listOf("我的", "发现", "精品", "影音", "抖音专区", "教育", "游戏", "应用", "管理", "福利")

    fun scene(wrap: Boolean = false) =
        FakeScene(
            tabs.mapIndexed { col, text -> FakeCell(text, 0, col) } + FakeCell("沙漠往事", 1, 2),
            tabs,
            10,
            "精品",
            wrap,
        )
}

class FakeTv(private val clock: Clock, private val scene: FakeScene = DangbeiHome.scene()) : IrTransmitter, FrameSource, Perception {
    override val isReady = true
    private var focusIndex = scene.initialFocus
    private var activeTab = scene.activeTab
    private var generation = 0
    var dropKeys = false
    val sentKeys = mutableListOf<RemoteKey>()
    private val cols = (scene.cells.maxOf { it.col } + 1).coerceAtLeast(1)
    private val rows = (scene.cells.maxOf { it.row } + 1).coerceAtLeast(1)

    private data class FakeFrame(override val capturedAtMs: Long, override val fixtureId: String, val screen: ScreenState) : Frame

    override suspend fun send(code: IrCode) {
        require(codes[code.name] == code) { "Unknown synthetic IR code" }
        sentKeys += code.name
        if (dropKeys) return
        val current = scene.cells[focusIndex]
        when (code.name) {
            RemoteKey.OK -> if (current.text in scene.tabs) activeTab = current.text
            RemoteKey.HOME, RemoteKey.BACK -> {
                focusIndex = scene.initialFocus
                activeTab = scene.activeTab
            }
            RemoteKey.UP, RemoteKey.DOWN, RemoteKey.LEFT, RemoteKey.RIGHT -> {
                val horizontal = code.name == RemoteKey.LEFT || code.name == RemoteKey.RIGHT
                val forward = code.name == RemoteKey.RIGHT || code.name == RemoteKey.DOWN
                val candidates =
                    scene.cells.indices.filter { index ->
                        val cell = scene.cells[index]
                        if (horizontal) {
                            cell.row == current.row && if (forward) cell.col > current.col else cell.col < current.col
                        } else if (forward) {
                            cell.row > current.row
                        } else {
                            cell.row < current.row
                        }
                    }
                val next =
                    candidates.minWithOrNull(
                        compareBy<Int> {
                            val cell = scene.cells[it]
                            abs(cell.col - current.col)
                        }.thenBy { abs(scene.cells[it].row - current.row) },
                    )
                if (next != null) {
                    focusIndex = next
                } else if (scene.wrap) {
                    val line =
                        scene.cells.indices.filter {
                            if (horizontal) scene.cells[it].row == current.row else scene.cells[it].col == current.col
                        }
                    focusIndex =
                        if (forward) {
                            line.minBy {
                                if (horizontal) scene.cells[it].col else scene.cells[it].row
                            }
                        } else {
                            line.maxBy { if (horizontal) scene.cells[it].col else scene.cells[it].row }
                        }
                }
            }
            else -> error("Unsupported fake key")
        }
        generation++
    }

    override suspend fun latest(): Frame = FakeFrame(clock.nowMs(), "fake-$generation", snapshot())

    override fun parse(frame: Frame): ScreenState = (frame as FakeFrame).screen

    private fun snapshot(): ScreenState {
        val elements =
            scene.cells.mapIndexed { index, cell ->
                UiElement(
                    "e$index",
                    cell.text,
                    NormRect(cell.col.toFloat() / cols, cell.row.toFloat() / rows, (cell.col + .9f) / cols, (cell.row + .9f) / rows),
                    cell.row,
                    cell.col,
                    1f,
                )
            }
        val focused = elements[focusIndex]
        val type = if (scene.cells[focusIndex].row == 0) PageType.TAB_BAR else PageType.LAUNCHER
        val canonical = listOf(type.name, activeTab.orEmpty()) + elements.map { it.text }.sorted()
        val fingerprint =
            MessageDigest.getInstance(
                "SHA-256",
            ).digest(canonical.joinToString("\u0000").toByteArray(Charsets.UTF_8)).joinToString("") {
                "%02x".format(it)
            }.take(16)
        val selected =
            scene.cells.mapIndexedNotNull {
                    index,
                    cell,
                ->
                cell.selected?.let { Selection("e$index", cell.text, SelectionKind.RADIO, it, 1f) }
            }
        return ScreenState(
            type, scene.tabs, activeTab,
            Focus(
                focused.id,
                focused.text,
                focused.bounds,
                1f,
            ),
            selected, elements, fingerprint = fingerprint, capturedAtMs = clock.nowMs(), parseConfidence = 1f,
        )
    }

    companion object {
        val codes: Map<RemoteKey, IrCode> = RemoteKey.entries.associateWith { IrCode(it, 0, 0, it.ordinal) }
    }
}

class FakeScreenObserver(private val tv: FakeTv, private val clock: Clock) : ScreenObserver {
    override suspend fun current(): ScreenState = tv.parse(tv.latest())

    override suspend fun waitUntilStable(): ScreenState {
        clock.sleep(10)
        return current()
    }
}
