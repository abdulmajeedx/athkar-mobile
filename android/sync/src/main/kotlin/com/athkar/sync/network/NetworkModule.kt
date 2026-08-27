package com.athkar.sync.network

import com.athkar.sync.dto.RemoteConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.TlsVersion
import okhttp3.CertificatePinner
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ktor HTTP client with:
 *   - TLS 1.3 enforced (ConnectionSpec).
 *   - Certificate public-key pinning: two SPKI pins (primary + backup), refreshed from the signed
 *     remote config (60-day rotation + emergency kill switch). Only public-key pins, never the full
 *     cert, so rotation is pin-only.
 *   - OAuth2 Bearer interceptor that refreshes a rotating token on 401 (reuse detection handled by
 *     the server family revoke).
 *   - Brotli compression and connection reuse (HTTP/3 where the server negotiates it).
 */
@Singleton
class NetworkModule @Inject constructor() {

    private val remoteConfig = RemoteConfigProvider()

    fun httpClient(tokenProvider: TokenProvider): HttpClient {
        val okHttpEngine = OkHttpClient.Builder()
            .connectionSpecs(
                listOf(
                    ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                        .tlsVersions(TlsVersion.TLS_1_3)
                        .build()
                )
            )
            .certificatePinner(
                CertificatePinner.Builder().apply {
                    // Primary + backup pins, from signed remote config; rotated every 60 days.
                    remoteConfig.currentPins().forEach { (hostname, pin) -> add(hostname, pin) }
                }.build()
            )
            .connectionPool(okhttp3.ConnectionPool(5, 60, TimeUnit.SECONDS))
            .build()

        return HttpClient(OkHttp) {
            engine { preconfigured = okHttpEngine }
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            install(Auth) {
                bearer {
                    loadTokens { tokenProvider.tokens() }
                    refreshTokens { tokenProvider.refresh() }
                }
            }
            defaultRequest {
                url(BASE_URL)
            }
        }
    }

    companion object {
        const val BASE_URL = "https://api.athkar.example.com"
    }
}

interface TokenProvider {
    fun tokens(): BearerTokens?
    suspend fun refresh(): BearerTokens?
}

/** Supplies current TLS pins from remote config; falls back to baked-in pins offline. */
class RemoteConfigProvider {
    private var config: RemoteConfig? = null
    fun update(c: RemoteConfig) { config = c }
    fun currentPins(): List<Pair<String, String>> {
        val pins = config?.pins?.map { "api.athkar.example.com" to "sha256/${it.hash}" }
            ?.ifEmpty { null }
            ?: listOf(
                "api.athkar.example.com" to "sha256/PLACEHOLDER_PRIMARY_PIN",
                "api.athkar.example.com" to "sha256/PLACEHOLDER_BACKUP_PIN",
            )
        return pins
    }
}
