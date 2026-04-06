// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.schedule

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import io.agents.pokeclaw.ClawApplication
import io.agents.pokeclaw.R
import io.agents.pokeclaw.service.ClawAccessibilityService
import io.agents.pokeclaw.service.ForegroundService
import io.agents.pokeclaw.ui.chat.ComposeChatActivity
import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.utils.XLog
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

/**
 * Fires at [ScheduledTask.triggerAtEpochMs]; runs the agent task on the main thread.
 */
class ScheduledTaskAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ScheduledTaskAlarmRcvr"
        const val EXTRA_TASK_ID = "task_id"
        private const val NOTIFY_CHANNEL = "pokeclaw_scheduled_tasks"
        private const val NOTIFY_BASE_ID = 7100
        private const val ONE_SHOT_RETRY_DELAY_MS = 120_000L
        private const val ONE_SHOT_MAX_RETRIES = 3
    }

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return
        val pendingResult = goAsync()
        val appCtx = context.applicationContext

        Handler(Looper.getMainLooper()).post {
            try {
                handleFire(appCtx, taskId)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun handleFire(context: Context, taskId: String) {
        val stored = ScheduledTaskStore.getById(taskId)
        if (stored == null) {
            XLog.w(TAG, "No task for id=$taskId")
            ScheduledTaskScheduler.cancelAlarm(context, taskId)
            return
        }

        ForegroundService.start(context)

        if (!KVUtils.hasLlmConfig()) {
            notifyUser(
                context,
                taskId,
                context.getString(R.string.scheduled_task_notify_no_llm_title),
                context.getString(R.string.scheduled_task_notify_no_llm_text),
            )
            advanceOrRemove(context, stored, started = false)
            return
        }

        if (!ClawAccessibilityService.isRunning()) {
            notifyUser(
                context,
                taskId,
                context.getString(R.string.scheduled_task_notify_no_a11y_title),
                context.getString(R.string.scheduled_task_notify_no_a11y_text),
            )
            advanceOrRemove(context, stored, started = false)
            return
        }

        val vm = try {
            ClawApplication.appViewModelInstance
        } catch (_: Throwable) {
            notifyUser(
                context,
                taskId,
                context.getString(R.string.scheduled_task_notify_error_title),
                context.getString(R.string.scheduled_task_notify_app_not_ready),
            )
            advanceOrRemove(context, stored, started = false)
            return
        }

        val messageId = "sched_${stored.id}"
        val started = vm.startLocalScheduledTask(stored.task, messageId)
        if (!started) {
            notifyUser(
                context,
                taskId,
                context.getString(R.string.scheduled_task_notify_busy_title),
                context.getString(R.string.scheduled_task_notify_busy_text),
            )
        }

        advanceOrRemove(context, stored, started = started)
    }

    private fun advanceOrRemove(context: Context, task: ScheduledTask, started: Boolean) {
        ScheduledTaskScheduler.cancelAlarm(context, task.id)
        if (task.repeatDaily) {
            if (started) {
                val updated = task.copy(
                    triggerAtEpochMs = nextDailyTrigger(task.triggerAtEpochMs),
                    retryCount = 0,
                )
                ScheduledTaskStore.update(updated)
                if (!ScheduledTaskScheduler.scheduleAlarm(context, updated)) {
                    XLog.e(TAG, "Failed to reschedule daily id=${task.id}")
                }
                return
            }
            if (task.retryCount < ONE_SHOT_MAX_RETRIES) {
                val updated = task.copy(
                    triggerAtEpochMs = System.currentTimeMillis() + ONE_SHOT_RETRY_DELAY_MS,
                    retryCount = task.retryCount + 1,
                )
                ScheduledTaskStore.update(updated)
                if (!ScheduledTaskScheduler.scheduleAlarm(context, updated)) {
                    ScheduledTaskStore.remove(task.id)
                }
                return
            }
            val updated = task.copy(
                triggerAtEpochMs = nextDailyTrigger(task.triggerAtEpochMs),
                retryCount = 0,
            )
            ScheduledTaskStore.update(updated)
            if (!ScheduledTaskScheduler.scheduleAlarm(context, updated)) {
                XLog.e(TAG, "Failed to reschedule daily id=${task.id}")
            }
            return
        }
        if (started) {
            ScheduledTaskStore.remove(task.id)
            return
        }
        if (task.retryCount >= ONE_SHOT_MAX_RETRIES) {
            ScheduledTaskStore.remove(task.id)
            return
        }
        val updated = task.copy(
            triggerAtEpochMs = System.currentTimeMillis() + ONE_SHOT_RETRY_DELAY_MS,
            retryCount = task.retryCount + 1,
        )
        ScheduledTaskStore.update(updated)
        if (!ScheduledTaskScheduler.scheduleAlarm(context, updated)) {
            ScheduledTaskStore.remove(task.id)
        }
    }

    private fun nextDailyTrigger(previousTriggerMs: Long): Long {
        val tz: TimeZone = TimeZone.getDefault()
        val cal: Calendar = GregorianCalendar(tz)
        cal.timeInMillis = previousTriggerMs
        cal.add(Calendar.DAY_OF_MONTH, 1)
        return cal.timeInMillis
    }

    private fun notifyUser(context: Context, taskId: String, title: String, text: String) {
        ensureChannel(context)
        val open = Intent(context, ComposeChatActivity::class.java)
        val pi = PendingIntent.getActivity(
            context,
            taskId.hashCode(),
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, NOTIFY_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFY_BASE_ID + (taskId.hashCode() and 0xff), notification)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(NOTIFY_CHANNEL) != null) return
        val ch = NotificationChannel(
            NOTIFY_CHANNEL,
            context.getString(R.string.scheduled_task_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        )
        nm.createNotificationChannel(ch)
    }
}
