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

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.vaachak.reader.core.domain.model.BookEntity
import org.vaachak.reader.core.utils.getCurrentTimeMillis

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY lastRead DESC")
    fun getAllBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books ORDER BY lastRead DESC")
    fun getAllBooksSortedByRecent(): Flow<List<BookEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBook(book: BookEntity)

    // Using bookHash as the true universal ID
    @Query("DELETE FROM books WHERE bookHash = :bookHash")
    suspend fun deleteBook(bookHash: String)

    @Query("DELETE FROM books WHERE localUri = :localUri")
    suspend fun deleteBookByUri(localUri: String)

    @Query("""
        UPDATE books 
        SET progress = :progress, 
            lastCfiLocation = :cfiLocation, 
            lastRead = :timestamp,
            updatedAt = :timestamp,
            isDirty = 1
        WHERE localUri = :localUri
    """)
    suspend fun updateBookProgressByUri(localUri: String, progress: Double, cfiLocation: String, timestamp: Long)

    @Query("""
        UPDATE books 
        SET progress = :progress,
            lastCfiLocation = :cfiLocation, 
            lastRead = :timestamp,
            updatedAt = :timestamp,
            isDirty = 1
        WHERE bookHash = :bookHash
    """)
    suspend fun updateProgressFromCloud(bookHash: String, progress: Double, cfiLocation: String, timestamp: Long)

    @Query("SELECT * FROM books WHERE localUri = :localUri LIMIT 1")
    suspend fun getBookByUri(localUri: String): BookEntity?

    @Query("UPDATE books SET lastRead = :timestamp, updatedAt = :timestamp, isDirty = 1 WHERE localUri = :localUri")
    suspend fun updateLastRead(localUri: String, timestamp: Long = getCurrentTimeMillis())

    @Query("SELECT EXISTS(SELECT 1 FROM books WHERE title = :title LIMIT 1)")
    suspend fun isBookExists(title: String): Boolean

    @Query("SELECT * FROM books WHERE lastRead > :timestamp")
    suspend fun getBooksModifiedSince(timestamp: Long): List<BookEntity>

    @Query("SELECT * FROM books WHERE bookHash = :bookHash LIMIT 1")
    suspend fun getBookByHash(bookHash: String): BookEntity?

    @Query("SELECT bookHash FROM books")
    suspend fun getAllBookHashes(): List<String>

    // Vault Sync Update
    @Query("""
        UPDATE books 
        SET lastCfiLocation = :cfi, 
            progress = :progress,
            lastRead = :timestamp,
            updatedAt = :timestamp,
            isDirty = 0
        WHERE bookHash = :bookHash AND updatedAt < :timestamp
    """)
    suspend fun updateBookMetadataFromSync(bookHash: String, cfi: String, progress: Double, timestamp: Long)
}

