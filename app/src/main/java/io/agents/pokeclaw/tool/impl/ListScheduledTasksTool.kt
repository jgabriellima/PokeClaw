// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool.impl

import com.google.gson.Gson
import io.agents.pokeclaw.ClawApplication
import io.agents.pokeclaw.R
import io.agents.pokeclaw.schedule.ScheduledTaskStore
import io.agents.pokeclaw.tool.BaseTool
import io.agents.pokeclaw.tool.ToolParameter
import io.agents.pokeclaw.tool.ToolResult

class ListScheduledTasksTool : BaseTool() {

    override fun getName() = "list_scheduled_tasks"

    override fun getDisplayName(): String =
        ClawApplication.instance.getString(R.string.tool_name_list_scheduled_tasks)

    override fun getDescriptionEN(): String =
        "List pending scheduled agent tasks (id, trigger time, repeat flag, task text preview)."

    override fun getDescriptionCN(): String =
        "列出待执行的已调度任务（id、触发时间、是否每日重复、任务摘要）。"

    override fun getParameters(): List<ToolParameter> = emptyList()

    override fun execute(params: Map<String, Any>): ToolResult {
        val tasks = ScheduledTaskStore.loadAll()
        val now = System.currentTimeMillis()
        val rows = tasks.map { t ->
            mapOf(
                "id" to t.id,
                "trigger_at_epoch_ms" to t.triggerAtEpochMs,
                "seconds_until_fire" to ((t.triggerAtEpochMs - now) / 1000).coerceAtLeast(0),
                "repeat_daily" to t.repeatDaily,
                "retry_count" to t.retryCount,
                "task_preview" to t.task.take(300),
            )
        }
        return ToolResult.success(Gson().toJson(mapOf("count" to rows.size, "tasks" to rows)))
    }
}
