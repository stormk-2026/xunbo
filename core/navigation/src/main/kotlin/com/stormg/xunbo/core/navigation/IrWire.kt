package com.stormg.xunbo.core.navigation

import com.stormg.xunbo.core.model.IrCode
import com.stormg.xunbo.core.model.IrFrameFormat
import com.stormg.xunbo.core.model.RemoteKey

object IrWire {
    fun valid(code: IrCode): Boolean = listOf(code.userCode1, code.userCode2, code.commandCode).all { it in 0..255 }

    fun encode(
        code: IrCode,
        format: IrFrameFormat,
    ): ByteArray {
        require(valid(code)) { "INVALID_IR_BYTE" }
        val bytes =
            when (format) {
                IrFrameFormat.RAW4_INV -> listOf(code.userCode1, code.userCode2, code.commandCode, code.commandCode xor 255)
                IrFrameFormat.A1F1 -> listOf(161, 241, code.userCode1, code.userCode2, code.commandCode)
            }
        return bytes.map(Int::toByte).toByteArray()
    }
}

class LearnedBytes(private val key: RemoteKey) {
    private val bytes = mutableListOf<Int>()

    fun accept(fragment: ByteArray): IrCode? {
        require(bytes.size + fragment.size <= 3) { "LEARN_PACKET_LENGTH" }
        bytes.addAll(fragment.map { it.toInt() and 255 })
        return if (bytes.size == 3) IrCode(key, bytes[0], bytes[1], bytes[2]) else null
    }
}
