// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.schedule

import java.util.UUID

/**
 * Persisted unit of work executed by the agent when [triggerAtEpochMs] fires.
 */
data class ScheduledTask(
    val id: String = UUID.randomUUID().toString(),
    val task: String,
    var triggerAtEpochMs: Long,
    val repeatDaily: Boolean = false,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    /** One-shot only: increments when the alarm fires but the task does not start (busy / prerequisites). */
    val retryCount: Int = 0,
)
