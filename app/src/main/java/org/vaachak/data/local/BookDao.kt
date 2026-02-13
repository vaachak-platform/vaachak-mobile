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

package org.vaachak.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY lastRead DESC")
    fun getAllBooks(): Flow<List<BookEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBook(book: BookEntity)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun deleteBook(id: Long)

    @Query("DELETE FROM books WHERE uriString = :uri")
    suspend fun deleteBookByUri(uri: String)

    @Query("""
    UPDATE books 
    SET progress = :progress, 
        lastLocationJson = :locationJson, 
        lastCfiLocation = :locationJson, 
        lastRead = :timestamp 
    WHERE uriString = :uriString
""")
    suspend fun updateBookProgress(uriString: String, progress: Double, locationJson: String, timestamp: Long)

    @Query("""
    UPDATE books 
    SET progress = :progress,
        lastCfiLocation = :cfi, 
        lastLocationJson = :cfi, 
        lastRead = :timestamp 
    WHERE id = :bookId
""")
    suspend fun updateProgressFromCloud(bookId: Long, progress: Double, cfi: String, timestamp: Long)

    @Query("SELECT * FROM books WHERE uriString = :uri LIMIT 1")
    suspend fun getBookByUri(uri: String): BookEntity?

    @Query("UPDATE books SET lastRead = :timestamp WHERE uriString = :uriString")
    suspend fun updateLastRead(uriString: String, timestamp: Long = System.currentTimeMillis())

    @Query("SELECT * FROM books ORDER BY lastRead DESC")
    fun getAllBooksSortedByRecent(): Flow<List<BookEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM books WHERE title = :title LIMIT 1)")
    suspend fun isBookExists(title: String): Boolean

    @Query("SELECT * FROM books WHERE lastRead > :timestamp")
    suspend fun getBooksModifiedSince(timestamp: Long): List<BookEntity>

    @Query("SELECT * FROM books WHERE bookHash = :hash LIMIT 1")
    suspend fun getBookByHash(hash: String): BookEntity?

    @Query("SELECT * FROM books WHERE id = :bookId LIMIT 1")
    suspend fun getBookById(bookId: Long): BookEntity?

    @Query("SELECT bookHash FROM books")
    suspend fun getAllBookHashes(): List<String>

    @Query("""
    UPDATE books 
    SET lastCfiLocation = :cfi, 
        lastLocationJson = :cfi, 
        lastRead= :timestamp,
        progress = :progress
    WHERE bookHash = :bookHash AND lastRead < :timestamp
""")
    suspend fun updateBookMetadataFromSync(
        bookHash: String,
        cfi: String,
        progress: Double,
        timestamp: Long
    )
}

