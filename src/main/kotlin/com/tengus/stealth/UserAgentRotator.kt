package com.tengus.stealth

import com.tengus.config.UserAgentConfig
import com.tengus.model.WeightedUserAgent
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Selects user-agent strings using weighted-random strategy with a
 * consecutive-different guarantee per domain.
 *
 * Validates: Requirements 9.1, 9.2, 9.3
 */
class UserAgentRotator(
    private val config: UserAgentConfig,
    private val random: Random = Random.Default
) {
    private val lastUsedByDomain = ConcurrentHashMap<String, String>()

    /**
     * Select a user-agent for the given [domain] using weighted-random strategy.
     * Guarantees consecutive calls for the same domain return different agents
     * when more than one agent is configured.
     */
    fun select(domain: String): String {
        val agents = config.agents
        require(agents.isNotEmpty()) { "No user-agents configured" }

        if (agents.size == 1) {
            val only = agents.first().userAgent
            lastUsedByDomain[domain] = only
            return only
        }

        val lastUsed = lastUsedByDomain[domain]
        val candidates = agents.filter { it.userAgent != lastUsed }

        val selected = weightedRandomSelect(candidates)
        lastUsedByDomain[domain] = selected.userAgent
        return selected.userAgent
    }

    /**
     * Returns the configured list of weighted user-agents.
     */
    fun availableAgents(): List<WeightedUserAgent> = config.agents

    private fun weightedRandomSelect(agents: List<WeightedUserAgent>): WeightedUserAgent {
        val totalWeight = agents.sumOf { it.weight }
        var roll = random.nextDouble() * totalWeight
        for (agent in agents) {
            roll -= agent.weight
            if (roll <= 0.0) return agent
        }
        return agents.last()
    }
}
