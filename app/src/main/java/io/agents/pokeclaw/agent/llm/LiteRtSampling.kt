// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.llm

import com.google.ai.edge.litertlm.SamplerConfig
import io.agents.pokeclaw.agent.AgentConfig

/** Builds LiteRT-LM [SamplerConfig] from cloud/local agent sampling fields. */
object LiteRtSampling {

    fun fromAgentConfig(cfg: AgentConfig): SamplerConfig = SamplerConfig(
        topK = 64,
        topP = (cfg.topP ?: 0.95).coerceIn(0.001, 1.0),
        temperature = cfg.temperature.coerceIn(0.0, 2.0),
    )
}
