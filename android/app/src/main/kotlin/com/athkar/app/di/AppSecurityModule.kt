package com.athkar.app.di

import com.athkar.app.security.AndroidKeystoreKeyProvider
import com.athkar.data.db.DbKeyProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppSecurityModule {

    @Binds
    @Singleton
    abstract fun bindDbKeyProvider(impl: AndroidKeystoreKeyProvider): DbKeyProvider
}
