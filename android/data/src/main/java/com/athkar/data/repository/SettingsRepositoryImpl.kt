package com.athkar.data.repository

import com.athkar.core.clock.Hlc
import com.athkar.core.crdt.LwwMap
import com.athkar.data.db.dao.SyncDao
import com.athkar.data.db.entity.SettingEntity
import com.athkar.domain.SettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * LWW-map CRDT-backed settings storage (single source of truth in SQLite). Merge against incoming
 * remote copies uses the same pointwise-max semantics proven in the core.
 */
class SettingsRepositoryImpl @Inject constructor(private val syncDao: SyncDao) : SettingsRepository {

    override fun observe(): Flow<LwwMap<String, Any?>> =
        syncDao.observeSettings().map { rows ->
            rows.fold(LwwMap.empty<String, Any?>()) { acc, r ->
                acc.set(r.key, Decoder.fromJson(r.valueJson), Hlc.decode(r.hlc), r.writerId)
            }
        }

    override suspend fun set(key: String, value: Any?, nowMillis: Long) {
        val prev = syncDao.allSettings().firstOrNull { it.key == key }
        val hlc = Hlc.tick(prev?.let { Hlc.decode(it.hlc) }, nowMillis)
        syncDao.upsertSetting(
            SettingEntity(key, Decoder.toJson(value), hlc.value, prev?.writerId ?: "local")
        )
    }
}

private object Decoder {
    fun toJson(v: Any?): String? = when (v) {
        null -> null
        is String -> "\"$v\""
        is Number -> v.toString()
        is Boolean -> v.toString()
        else -> org.json.JSONObject().put("v", v.toString()).toString()
    }

    fun fromJson(json: String?): Any? = when {
        json == null -> null
        json.startsWith("\"") -> json.substring(1, json.length - 1)
        json == "true" -> true
        json == "false" -> false
        else -> json.toDoubleOrNull() ?: (json.toLongOrNull() ?: json)
    }
}
