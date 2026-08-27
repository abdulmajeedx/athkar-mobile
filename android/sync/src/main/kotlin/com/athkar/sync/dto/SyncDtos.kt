package com.athkar.sync.dto

import kotlinx.serialization.Serializable

@Serializable
data class TokenResponse(
    val access_token: String,
    val refresh_token: String,
    val expires_in: Int,
    val token_type: String,
)

@Serializable
data class SyncWrite(
    val idempotency_key: String,
    val op_type: String,
    val entity_type: String,
    val entity_id: String,
    val field: String? = null,
    val value: String? = null,
    val client_hlc: Long,
    val writer_id: String,
)

@Serializable
data class WriteAck(
    val idempotency_key: String,
    val server_hlc: Long,
    val status: String,
    val error: String? = null,
)

@Serializable
data class PushResult(
    val acks: List<WriteAck> = emptyList(),
)

@Serializable
data class ChangeItem(
    val entity_type: String,
    val entity_id: String,
    val server_hlc: Long,
    val op_type: String,
    val payload: Map<String, String?> = emptyMap(),
)

@Serializable
data class ChangesPage(
    val changes: List<ChangeItem> = emptyList(),
    val next_cursor: String,
    val has_more: Boolean,
)

@Serializable
data class AckBody(val cursor: String)

@Serializable
data class ErrorMessage(
    val code: String,
    val message: String,
    val request_id: String,
    val retry_after: Int? = null,
)

@Serializable
data class RemoteConfig(
    val version: Int,
    val issued_at: Long,
    val signature: String,
    val flags: Map<String, Boolean> = emptyMap(),
    val pins: List<Pin> = emptyList(),
)

@Serializable
data class Pin(val type: String, val hash: String)
