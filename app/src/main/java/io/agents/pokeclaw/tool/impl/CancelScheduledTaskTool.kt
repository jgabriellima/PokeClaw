// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool.impl

import io.agents.pokeclaw.ClawApplication
import io.agents.pokeclaw.R
import io.agents.pokeclaw.schedule.ScheduledTaskScheduler
import io.agents.pokeclaw.schedule.ScheduledTaskStore
import io.agents.pokeclaw.tool.BaseTool
import io.agents.pokeclaw.tool.ToolParameter
import io.agents.pokeclaw.tool.ToolResult

class CancelScheduledTaskTool : BaseTool() {

    override fun getName() = "cancel_scheduled_task"

    override fun getDisplayName(): String =
        ClawApplication.instance.getString(R.string.tool_name_cancel_scheduled_task)

    override fun getDescriptionEN(): String =
        "Cancel a pending scheduled task by id (from list_scheduled_tasks or schedule_task response)."

    override fun getDescriptionCN(): String =
        "按 id 取消待执行任务（id 来自 list_scheduled_tasks 或 schedule_task 的返回）。"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter("task_id", "string", "The scheduled_task_id to cancel.", true),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val id = requireString(params, "task_id").trim()
        if (id.isEmpty()) {
            return ToolResult.error("task_id must not be empty")
        }
        val ctx = ClawApplication.instance
        ScheduledTaskScheduler.cancelAlarm(ctx, id)
        val removed = ScheduledTaskStore.remove(id)
        return if (removed != null) {
            ToolResult.success("Cancelled scheduled task id=$id")
        } else {
            ToolResult.error("No scheduled task with id=$id")
        }
    }
}
