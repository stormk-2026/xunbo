package com.stormg.xunbo.core.agent

import com.stormg.xunbo.core.model.Skill
import com.stormg.xunbo.core.model.SkillStore
import com.stormg.xunbo.core.model.TraceSink
import com.stormg.xunbo.core.model.TraceStep

class InMemorySkillStore : SkillStore {
    private val skills = mutableMapOf<Pair<String, String>, Skill>()

    override suspend fun find(
        deviceFingerprint: String,
        goalKey: String,
    ): Skill? =
        synchronized(skills) {
            skills[deviceFingerprint to goalKey]
        }

    override suspend fun save(skill: Skill) {
        synchronized(skills) { skills[skill.deviceFingerprint to skill.goalKey] = skill }
    }
}

class InMemoryTraceSink : TraceSink {
    private val steps = mutableListOf<TraceStep>()

    override fun append(step: TraceStep) {
        synchronized(steps) { steps.add(step) }
    }

    override fun all(): List<TraceStep> = synchronized(steps) { steps.toList() }
}
