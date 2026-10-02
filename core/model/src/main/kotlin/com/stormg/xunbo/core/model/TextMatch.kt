package com.stormg.xunbo.core.model

import java.text.Normalizer
import java.util.Locale

object TextMatch {
    fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT).replace(Regex("[\\s\\p{Z}\\p{P}]+"), "")

    fun matches(
        expected: String,
        actual: String,
    ): Boolean {
        val a = normalize(expected)
        val b = normalize(actual)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        val shorter = if (a.length <= b.length) a else b
        return shorter.length >= 2 && !shorter.all(Char::isDigit) && (a.contains(b) || b.contains(a))
    }

    fun matchesEpisode(
        expected: Int,
        actual: String,
    ): Boolean {
        val value = normalize(actual)
        val match = Regex("(?:第)?([0-9]+)(?:集)?").matchEntire(value) ?: return false
        return expected > 0 && match.groupValues[1].toIntOrNull() == expected
    }
}

class DefaultDangerGuard : DangerGuard {
    private val words =
        listOf("重启", "恢复出厂", "出厂设置", "关机", "关闭系统", "格式化", "清除", "清空", "删除", "卸载", "重置", "购买", "订购", "开通", "支付", "付费", "续费", "会员")

    override fun isDanger(text: String): Boolean {
        val widthNormalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
        if (widthNormalized.contains('¥') || widthNormalized.contains('￥')) return true
        if (Regex("\\d+(\\.\\d+)?\\s*元|元\\s*/\\s*[月季]").containsMatchIn(widthNormalized)) return true
        val normalized = TextMatch.normalize(text)
        return words.any(normalized::contains)
    }
}

class IrKeyNameParser(private val onUnknown: (String) -> Unit = {}) {
    fun parse(name: String): RemoteKey? {
        val aliases =
            mapOf(
                "上" to RemoteKey.UP, "下" to RemoteKey.DOWN, "左" to RemoteKey.LEFT, "右" to RemoteKey.RIGHT,
                "确定" to RemoteKey.OK, "ENTER" to RemoteKey.OK, "返回" to RemoteKey.BACK, "设置" to RemoteKey.MENU,
                "电源" to RemoteKey.POWER, "主页" to RemoteKey.HOME, "首页" to RemoteKey.HOME,
                "音量+" to RemoteKey.VOL_UP, "音量-" to RemoteKey.VOL_DOWN,
            )
        val value = Normalizer.normalize(name.trim(), Normalizer.Form.NFKC).uppercase(Locale.ROOT)
        return (RemoteKey.entries.firstOrNull { it.name == value } ?: aliases[value]).also {
            if (it == null) onUnknown(name)
        }
    }
}

object GoalKeys {
    fun of(intent: TaskIntent): String =
        buildString {
            append(intent.action.name.lowercase(Locale.ROOT))
            append(':')
            append(intent.title ?: intent.targetValue ?: intent.raw)
            intent.episode?.let { append("|ep=$it") }
            intent.hintTab?.let { append("|tab=$it") }
        }
}
