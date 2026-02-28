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

package org.vaachak.reader.core.data.local

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor // Ensure this is imported!
import org.vaachak.reader.core.domain.model.BookEntity
import org.vaachak.reader.core.domain.model.HighlightEntity
import org.vaachak.reader.core.domain.model.OpdsEntity
import org.vaachak.reader.core.domain.model.ProfileEntity
import org.vaachak.reader.core.domain.model.SyncVaultEntity

@Database(
    entities = [
        BookEntity::class,
        HighlightEntity::class,
        OpdsEntity::class,
        SyncVaultEntity::class,
        ProfileEntity::class
    ],
    version = 2,
    exportSchema = false
)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun highlightDao(): HighlightDao
    abstract fun opdsDao(): OpdsDao
    abstract fun syncVaultDao(): SyncVaultDao

    abstract fun profileDao(): ProfileDao

    companion object {
        const val DATABASE_NAME = "vaachak_db.db"
    }
}

// THIS IS THE CRITICAL LINE THAT FIXES YOUR ERROR:
// It tells Kotlin "Don't worry, Room KSP will generate the 'actual' object for Android/iOS"
@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase>
