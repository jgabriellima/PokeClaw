// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.net.Uri
import android.provider.Settings
import io.agents.pokeclaw.ui.chat.ComposeChatActivity
import io.agents.pokeclaw.utils.XLog

/**
 * Schedules [ScheduledTask] using [AlarmManager.setAlarmClock] when possible.
 */
object ScheduledTaskScheduler {

    private const val TAG = "ScheduledTaskScheduler"

    fun pendingIntentRequestCode(taskId: String): Int = taskId.hashCode() and 0x7fff_ffff

    fun scheduleAlarm(context: Context, task: ScheduledTask): Boolean {
        val appCtx = context.applicationContext
        val am = appCtx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            XLog.w(TAG, "Exact alarms not allowed; open settings")
            return false
        }
        val triggerAt = task.triggerAtEpochMs
        if (triggerAt <= System.currentTimeMillis()) {
            XLog.w(TAG, "Trigger time in the past, not scheduling id=${task.id}")
            return false
        }
        val operation = taskPendingIntent(appCtx, task.id)
        val showIntent = Intent(appCtx, ComposeChatActivity::class.java).let { i ->
            PendingIntent.getActivity(
                appCtx,
                pendingIntentRequestCode(task.id) + 1,
                i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val info = AlarmManager.AlarmClockInfo(triggerAt, showIntent)
        try {
            am.setAlarmClock(info, operation)
            XLog.i(TAG, "Scheduled id=${task.id} at=$triggerAt repeatDaily=${task.repeatDaily}")
            return true
        } catch (e: Exception) {
            XLog.e(TAG, "setAlarmClock failed", e)
            return false
        }
    }

    fun cancelAlarm(context: Context, taskId: String) {
        val appCtx = context.applicationContext
        val am = appCtx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = taskPendingIntent(appCtx, taskId)
        am.cancel(pi)
        pi.cancel()
    }

    /**
     * Re-register alarms after reboot or process death (store is source of truth).
     */
    fun rescheduleAllPending(context: Context) {
        val appCtx = context.applicationContext
        val now = System.currentTimeMillis()
        val tasks = ScheduledTaskStore.loadAll()
        for (t in tasks) {
            if (t.triggerAtEpochMs > now) {
                if (!scheduleAlarm(appCtx, t)) {
                    XLog.w(TAG, "rescheduleAllPending: failed id=${t.id}")
                }
            }
        }
    }

    private fun taskPendingIntent(context: Context, taskId: String): PendingIntent {
        val intent = Intent(context, ScheduledTaskAlarmReceiver::class.java).putExtra(
            ScheduledTaskAlarmReceiver.EXTRA_TASK_ID,
            taskId
        )
        return PendingIntent.getBroadcast(
            context,
            pendingIntentRequestCode(taskId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun canScheduleExactAlarms(context: Context): Boolean {
        val am = context.applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
    }

    fun exactAlarmSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                data = Uri.parse("package:${context.packageName}")
            }
        }
}
