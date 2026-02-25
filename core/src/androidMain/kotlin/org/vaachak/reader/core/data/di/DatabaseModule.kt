/*
 *  Copyright (c) 2026 Piyush Daiya
 *  *
 *  * Permission is hereby granted, free of charge, to any person obtaining a copy
 *  * of this software and associated documentation files (the "Software"), to deal
 *  * in the Software without restriction, including without limitation the rights
 *  * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  * copies of the Software, and to permit persons to whom the Software is
 *  * furnished to do so, subject to the following conditions:
 *  *
 *  * The above copyright notice and this permission notice shall be included in all
 *  * copies or substantial portions of the Software.
 *  *
 *  * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  * SOFTWARE.
 */

package org.vaachak.reader.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.vaachak.reader.core.data.local.AppDatabase
import org.vaachak.reader.core.data.local.BookDao
import org.vaachak.reader.core.data.local.HighlightDao
import javax.inject.Singleton
import org.vaachak.reader.core.data.local.OpdsDao
import org.vaachak.reader.core.data.local.getDatabaseBuilder
import org.vaachak.reader.core.security.CryptoManager

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    // --- ROOM DATABASE SETUP ---
    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return getDatabaseBuilder(context)
            .setDriver(BundledSQLiteDriver()) // The KMP multiplatform driver
            // .fallbackToDestructiveMigration() // Use this while iterating in Beta
            .build()
    }

    @Provides
    fun provideHighlightDao(database: AppDatabase): HighlightDao {
        return database.highlightDao()
    }

    @Provides
    @Singleton
    fun provideBookDao(database: AppDatabase): BookDao {
        return database.bookDao()
    }

    // --- THIS IS THE MISSING PART CAUSING YOUR ERROR ---
    @Provides
    fun provideOpdsDao(db: AppDatabase): OpdsDao = db.opdsDao()

    // --- DATASTORE SETUP ---
    // This creates the single source of truth for "user_settings".
    // It replaces the 'Context.dataStore' extension property to prevents crashes.
    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> {
        return PreferenceDataStoreFactory.create(
            produceFile = { context.preferencesDataStoreFile("user_settings") }
        )
    }
    @Provides
    @Singleton
    fun provideCryptoManager(): CryptoManager {
        return CryptoManager()
    }
    @Provides
    fun provideSyncVaultDao(database: AppDatabase): org.vaachak.reader.core.data.local.SyncVaultDao {
        return database.syncVaultDao()
    }
}