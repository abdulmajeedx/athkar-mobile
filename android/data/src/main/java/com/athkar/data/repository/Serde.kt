package com.athkar.data.repository

import com.athkar.core.domain.AdhkarReminder
import org.json.JSONArray
import org.json.JSONObject

/**
 * Minimal JSON serialization for outbox payloads. Kept dependency-light using platform JSON (org.json,
 * bundled on Android) so outbox rows stay human-debuggable and match the OpenAPI SyncWrite schema.
 */
object Serde {
    fun toJson(r: AdhkarReminder): String = JSONObject()
        .put("entityId", r.id)
        .put("field", "entity")
        .put("title", r.title)
        .put("body", r.body)
        .put("targetCount", r.targetCount)
        .put("times", r.times?.let { JSONArray(it.toList()) })
        .put("catOrder", r.catOrder)
        .put("pinned", r.pinned)
        .put("hlc", r.hlc.value)
        .put("writerId", r.writerId)
        .put("tombstoned", r.tombstoned)
        .toString()

    fun toJson(map: Map<String, Any?>): String = JSONObject(map).toString()
}
