package com.stormg.xunbo.core.agent

import com.stormg.xunbo.core.model.IrCode
import com.stormg.xunbo.core.model.IrKeyNameParser
import com.stormg.xunbo.core.model.RemoteKey
import com.stormg.xunbo.core.navigation.IrWire

data class ImportRow(val name: String, val userCode1: Int, val userCode2: Int, val commandCode: Int)

data class ImportPlan(
    val candidates: Map<RemoteKey, IrCode>,
    val firstCandidates: Map<RemoteKey, IrCode>,
    val existing: Set<RemoteKey>,
    val unknown: Int,
    val invalid: Int,
    val conflicts: Int,
) {
    fun selected(overwrite: Boolean): List<IrCode> =
        (if (overwrite) candidates else firstCandidates).filterKeys { overwrite || it !in existing }.values.toList()
}

object ImportPlanner {
    fun preview(
        rows: List<ImportRow>,
        existing: Map<RemoteKey, IrCode>,
    ): ImportPlan {
        val candidates = linkedMapOf<RemoteKey, IrCode>()
        val first = linkedMapOf<RemoteKey, IrCode>()
        var unknown = 0
        var invalid = 0
        var conflicts = 0
        for (row in rows) {
            val key = IrKeyNameParser().parse(row.name)
            if (key == null) {
                unknown++
                continue
            }
            val code = IrCode(key, row.userCode1, row.userCode2, row.commandCode)
            if (!IrWire.valid(code)) {
                invalid++
                continue
            }
            if (key in existing || key in candidates) conflicts++
            first.putIfAbsent(key, code)
            candidates[key] = code
        }
        return ImportPlan(candidates, first, existing.keys.toSet(), unknown, invalid, conflicts)
    }
}
