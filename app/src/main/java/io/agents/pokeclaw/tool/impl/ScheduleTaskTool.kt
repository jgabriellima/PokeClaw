// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool.impl

import com.google.gson.Gson
import io.agents.pokeclaw.ClawApplication
import io.agents.pokeclaw.R
import io.agents.pokeclaw.schedule.ScheduledTask
import io.agents.pokeclaw.schedule.ScheduledTaskScheduler
import io.agents.pokeclaw.schedule.ScheduledTaskStore
import io.agents.pokeclaw.tool.BaseTool
import io.agents.pokeclaw.tool.ToolParameter
import io.agents.pokeclaw.tool.ToolResult

/**
 * Agent tool: schedule a future LOCAL task via [android.app.AlarmManager] (alarm clock).
 */
class ScheduleTaskTool : BaseTool() {

    companion object {
        private const val MIN_FUTURE_MS = 10_000L
        private const val MAX_MINUTES = 525_600 // 1 year
    }

    override fun getName() = "schedule_task"

    override fun getDisplayName(): String =
        ClawApplication.instance.getString(R.string.tool_name_schedule_task)

    override fun getDescriptionEN(): String =
        "Schedule the device agent to run a task later (cron-like). Provide exactly one of run_in_minutes or run_at_epoch_ms. " +
            "Uses the system alarm clock; exact alarms may require user permission on Android 12+. " +
            "Optional repeat_daily runs the same task every day after each successful run."

    override fun getDescriptionCN(): String =
        "安排设备代理在稍后执行任务（类似定时任务）。必须且只能提供 run_in_minutes 或 run_at_epoch_ms 之一。" +
            "使用系统闹钟；Android 12+ 可能需要用户授予精确闹钟权限。repeat_daily 为 true 时每天重复执行。"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter("task", "string", "Full natural-language instruction for the agent when the alarm fires.", true),
        ToolParameter(
            "run_in_minutes",
            "integer",
            "Execute after this many minutes from now (1–525600). Omit if run_at_epoch_ms is set.",
            false
        ),
        ToolParameter(
            "run_at_epoch_ms",
            "integer",
            "Absolute time in milliseconds since Unix epoch. Must be in the future. Omit if run_in_minutes is set.",
            false
        ),
        ToolParameter(
            "repeat_daily",
            "boolean",
            "If true, reschedule every calendar day after each run. Default false.",
            false
        ),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val ctx = ClawApplication.instance
        val taskText = requireString(params, "task").trim()
        if (taskText.isEmpty()) {
            return ToolResult.error("task must not be empty")
        }

        val hasMinutes = params.containsKey("run_in_minutes") && params["run_in_minutes"] != null &&
            params["run_in_minutes"].toString().isNotBlank()
        val hasEpoch = params.containsKey("run_at_epoch_ms") && params["run_at_epoch_ms"] != null &&
            params["run_at_epoch_ms"].toString().isNotBlank()

        if (hasMinutes == hasEpoch) {
            return ToolResult.error("Provide exactly one of: run_in_minutes OR run_at_epoch_ms")
        }

        val now = System.currentTimeMillis()
        val triggerAt = when {
            hasMinutes -> {
                val m = optionalInt(params, "run_in_minutes", 0)
                if (m < 1 || m > MAX_MINUTES) {
                    return ToolResult.error("run_in_minutes must be between 1 and $MAX_MINUTES")
                }
                now + m * 60_000L
            }
            else -> {
                val at = optionalLong(params, "run_at_epoch_ms", 0)
                if (at <= now + MIN_FUTURE_MS) {
                    return ToolResult.error("run_at_epoch_ms must be at least ~${MIN_FUTURE_MS / 1000}s in the future")
                }
                at
            }
        }

        if (!ScheduledTaskScheduler.canScheduleExactAlarms(ctx)) {
            return ToolResult.error(
                "Exact alarms are not allowed for this app. Open Settings → Apps → PokeClaw → " +
                    "Alarms & reminders (or Special app access) and allow alarms, then try again."
            )
        }

        val repeatDaily = optionalBoolean(params, "repeat_daily", false)
        val entry = ScheduledTask(
            task = taskText,
            triggerAtEpochMs = triggerAt,
            repeatDaily = repeatDaily,
        )

        if (!ScheduledTaskStore.add(entry)) {
            return ToolResult.error("Too many scheduled tasks (max ${ScheduledTaskStore.MAX_TASKS})")
        }

        if (!ScheduledTaskScheduler.scheduleAlarm(ctx, entry)) {
            ScheduledTaskStore.remove(entry.id)
            return ToolResult.error("Failed to register alarm with the system")
        }

        val summary = mapOf(
            "scheduled_task_id" to entry.id,
            "trigger_at_epoch_ms" to entry.triggerAtEpochMs,
            "repeat_daily" to entry.repeatDaily,
            "task_preview" to taskText.take(200),
        )
        return ToolResult.success(Gson().toJson(summary))
    }
}
