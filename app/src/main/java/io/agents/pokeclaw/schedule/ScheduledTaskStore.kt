// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.schedule

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.agents.pokeclaw.utils.KVUtils

/**
 * MMKV-backed list of scheduled tasks (JSON).
 */
object ScheduledTaskStore {

    private const val KEY = "pokeclaw_scheduled_tasks_v1"
    private val gson = Gson()
    private val lock = Any()

    private val listType = object : TypeToken<MutableList<ScheduledTask>>() {}.type

    fun loadAll(): MutableList<ScheduledTask> {
        synchronized(lock) {
            val json = KVUtils.getString(KEY, "")
            if (json.isEmpty()) return mutableListOf()
            return try {
                gson.fromJson<MutableList<ScheduledTask>>(json, listType) ?: mutableListOf()
            } catch (_: Exception) {
                mutableListOf()
            }
        }
    }

    fun saveAll(tasks: List<ScheduledTask>) {
        synchronized(lock) {
            KVUtils.putString(KEY, gson.toJson(tasks))
            KVUtils.sync()
        }
    }

    fun getById(id: String): ScheduledTask? = loadAll().find { it.id == id }

    fun add(task: ScheduledTask): Boolean {
        synchronized(lock) {
            val list = loadAll()
            if (list.size >= MAX_TASKS) return false
            list.add(task)
            saveAll(list)
            return true
        }
    }

    fun remove(id: String): ScheduledTask? {
        synchronized(lock) {
            val list = loadAll()
            val idx = list.indexOfFirst { it.id == id }
            if (idx < 0) return null
            val removed = list.removeAt(idx)
            saveAll(list)
            return removed
        }
    }

    fun update(task: ScheduledTask) {
        synchronized(lock) {
            val list = loadAll()
            val idx = list.indexOfFirst { it.id == task.id }
            if (idx < 0) return
            list[idx] = task
            saveAll(list)
        }
    }

    const val MAX_TASKS = 50
}
