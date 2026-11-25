package org.vaachak.reader.core.data.repository

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CatalogIoDispatcher

@Module
@InstallIn(SingletonComponent::class)
object CatalogDispatcherModule {
    @Provides
    @CatalogIoDispatcher
    fun provideCatalogIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
}
