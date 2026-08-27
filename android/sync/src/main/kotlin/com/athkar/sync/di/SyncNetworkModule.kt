package com.athkar.sync.di

import com.athkar.sync.network.NetworkModule
import com.athkar.sync.network.RemoteConfigProvider
import com.athkar.sync.network.TokenProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import javax.inject.Singleton

/**
 * Wires the Ktor HTTP client (TLS 1.3 + certificate pinning + OAuth bearer) for the sync worker.
 * A real [TokenProvider] (OAuth 2.1 + PKCE against the Keystore-held refresh token) replaces the
 * default no-op in the reference app.
 */
@Module
@InstallIn(SingletonComponent::class)
object SyncNetworkModule {

    @Provides
    @Singleton
    fun provideRemoteConfigProvider(): RemoteConfigProvider = RemoteConfigProvider()

    @Provides
    @Singleton
    fun provideTokenProvider(): TokenProvider = NoopTokenProvider()

    @Provides
    @Singleton
    fun provideHttpClient(network: NetworkModule, tokens: TokenProvider): HttpClient =
        network.httpClient(tokens)
}

class NoopTokenProvider : TokenProvider {
    override fun tokens(): io.ktor.client.plugins.auth.providers.BearerTokens? = null
    override suspend fun refresh() = null
}
