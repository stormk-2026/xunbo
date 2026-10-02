package com.stormg.xunbo.core.testing

import com.stormg.xunbo.core.agent.ImportPlanner
import com.stormg.xunbo.core.agent.ImportRow
import com.stormg.xunbo.core.model.IrFrameFormat
import com.stormg.xunbo.core.navigation.IrWire
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class IrFixtureTest {
    @Serializable
    private data class Vector(val name: String, val u1: Int, val u2: Int, val cmd: Int, val format: IrFrameFormat, val expected: List<Int>)

    @Test
    fun replaySyntheticWireFixtures() {
        // Synthetic protocol vectors only; no learned device codes or external resources.
        val text =
            """
            [
              {"name":"上","u1":0,"u2":255,"cmd":128,"format":"RAW4_INV","expected":[0,255,128,127]},
              {"name":"确定","u1":18,"u2":52,"cmd":0,"format":"A1F1","expected":[161,241,18,52,0]},
              {"name":"BACK","u1":255,"u2":0,"cmd":255,"format":"RAW4_INV","expected":[255,0,255,0]},
              {"name":"音量+","u1":1,"u2":2,"cmd":3,"format":"A1F1","expected":[161,241,1,2,3]}
            ]
            """.trimIndent()
        val vectors = Json.decodeFromString<List<Vector>>(text)
        assertEquals(4, vectors.size)
        vectors.forEach { vector ->
            val code =
                ImportPlanner.preview(listOf(ImportRow(vector.name, vector.u1, vector.u2, vector.cmd)), emptyMap())
                    .selected(false).single()
            assertEquals(vector.expected, IrWire.encode(code, vector.format).map { it.toInt() and 255 })
        }
    }
}
