package com.athkar.sync.network

import com.athkar.sync.dto.ChangesPage
import com.athkar.sync.dto.PushResult
import com.athkar.sync.dto.SyncWrite
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.path
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Typed client over the OpenAPI contract endpoints used by the sync worker:
 *   - POST /sync/push   (push outbox, idempotent)
 *   - GET  /sync/changes (cursor-based pull)
 *   - POST /sync/ack    (advance the acknowledged cursor)
 */
@Singleton
class SyncApi @Inject constructor(
    private val client: HttpClient,
) {
    suspend fun pushChanges(writes: List<SyncWrite>): PushResult =
        client.post {
            url { path("sync", "push") }
            contentType(ContentType.Application.Json)
            setBody(PushRequest(writes))
        }.body()

    suspend fun getChanges(cursor: String): ChangesPage =
        client.get { url { path("sync", "changes"); parameters.append("cursor", cursor) } }.body()

    suspend fun ack(cursor: String) {
        client.post {
            url { path("sync", "ack") }
            contentType(ContentType.Application.Json)
            setBody(mapOf("cursor" to cursor))
        }
    }
}

@kotlinx.serialization.Serializable
data class PushRequest(val items: List<SyncWrite>)
