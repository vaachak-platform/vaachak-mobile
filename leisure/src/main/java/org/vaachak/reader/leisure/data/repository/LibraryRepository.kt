/*
 * Copyright (c) 2026 Piyush Daiya
 * *
 * * Permission is hereby granted, free of charge, to any person obtaining a copy
 * * of this software and associated documentation files (the "Software"), to deal
 * * in the Software without restriction, including without limitation the rights
 * * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * * copies of the Software, and to permit persons to whom the Software is
 * * furnished to do so, subject to the following conditions:
 * *
 * * The above copyright notice and this permission notice shall be included in all
 * * copies or substantial portions of the Software.
 * *
 * * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * * SOFTWARE.
 */

package org.vaachak.reader.leisure.data.repository

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.vaachak.reader.leisure.data.local.BookDao
import org.vaachak.reader.leisure.data.local.BookEntity
import org.vaachak.reader.leisure.ui.reader.ReadiumManager
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for managing the user's library of books.
 * This class handles all data operations related to books, including importing,
 * querying, and managing book files and metadata. It acts as a single source
 * of truth for book data, abstracting the underlying data sources (local database,
 * file system) from the UI and business logic.
 *
 * @param bookDao The Data Access Object for book entities.
 * @param readiumManager The manager for handling Readium-related tasks like parsing publications.
 * @param platformHelper A helper class to abstract platform-specific operations like file access.
 */
@Singleton
class LibraryRepository @Inject constructor(
    private val bookDao: BookDao,
    private val readiumManager: ReadiumManager,
    private val platformHelper: LibraryPlatformHelper // Replaces Context
) {

    /**
     * Checks if a book with the given title already exists in the library.
     *
     * @param title The title of the book to check.
     * @return `true` if a book with the same title exists, `false` otherwise.
     */
    suspend fun isBookDuplicate(title: String): Boolean {
        return bookDao.isBookExists(title)
    }

    /**
     * Retrieves a map of all local books, mapping their titles to their content URIs.
     * This is primarily used to allow opening books directly from a catalog or list view.
     * The books are sorted by the most recently accessed.
     *
     * @return A [Map] where keys are book titles (String) and values are their corresponding URI strings.
     *         Returns an empty map if the library is empty or if an error occurs.
     */
    suspend fun getLocalBookMap(): Map<String, String> = withContext(Dispatchers.IO) {
        try {
            bookDao.getAllBooksSortedByRecent().first().associate { it.title to it.uriString }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /**
     * Imports a book into the library from a local URI (e.g., from device storage).
     * This function handles the entire import process:
     * 1. Checks for duplicates in the database.
     * 2. Generates a unique MD5 hash of the book file for synchronization purposes.
     * 3. Persists URI permissions to ensure long-term access.
     * 4. Parses the book's metadata using Readium.
     * 5. Saves the book's cover image to local storage.
     * 6. Inserts the new book's details into the local database.
     *
     * @param uri The content [Uri] of the book file to import.
     * @return A [Result] object containing a success message or an [Exception] if the import fails.
     */
    suspend fun importBook(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        // 1. DB Check (Fast fail)
        val existing = bookDao.getBookByUri(uri.toString())
        if (existing != null) return@withContext Result.failure(Exception("Book already in library"))

        try {
            // 2. GENERATE HASH (Critical for Sync)
            // We do this before heavy parsing to ensure we can identify the file uniquely.
            // Requires 'openInputStream' to be added to LibraryPlatformHelper
            val bookHash = platformHelper.openInputStream(uri)?.use { stream ->
                calculateMd5Hash(stream)
            } ?: return@withContext Result.failure(Exception("Could not read file to generate hash"))

            // 3. CHECK HASH DUPLICATE (Optional but recommended)
            // If a file with same content exists (even if named differently), we can flag it.
            // val hashExists = bookDao.getBookByHash(bookHash)
            // if (hashExists != null) return ...

            // 4. PLATFORM: Persist Permissions
            platformHelper.takePersistableUriPermission(uri)

            // 5. READIUM: Parse Metadata
            val publication = readiumManager.openEpubFromUri(uri)
                ?: return@withContext Result.failure(Exception("Failed to parse book"))

            val title = publication.metadata.title ?: "Unknown Title"

            // Duplicate Check (Title match fallback)
            if (bookDao.isBookExists(title)) {
                readiumManager.closePublication()
                return@withContext Result.failure(Exception("Duplicate: '$title' is already in your library."))
            }

            val author = publication.metadata.authors.firstOrNull()?.name?.toString() ?: "Unknown Author"

            // 6. PLATFORM: Save Cover
            var savedCoverPath: String? = null
            try {
                val bitmap = readiumManager.getPublicationCover(publication)
                if (bitmap != null) {
                    savedCoverPath = platformHelper.saveCoverBitmapToStorage(bitmap, title)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // 7. SAVE TO DB
            val newBook = BookEntity(
                title = title,
                author = author,
                uriString = uri.toString(),

                // NEW SYNC FIELDS
                bookHash = bookHash,
                lastCfiLocation = null,

                coverPath = savedCoverPath,
                addedDate = System.currentTimeMillis(),
                lastRead = 0L,
                progress = 0.0
            )
            bookDao.insertBook(newBook)

            readiumManager.closePublication()
            return@withContext Result.success("Book added successfully")

        } catch (e: Exception) {
            readiumManager.closePublication()
            return@withContext Result.failure(e)
        }
    }

    /**
     * Adds a book that has been downloaded (e.g., from an OPDS feed) to the library.
     * This method assumes the book file is already stored locally.
     *
     * @param bookFile The [File] object pointing to the downloaded book.
     * @param title The title of the book.
     * @param author The author of the book.
     * @param coverFile An optional [File] object for the book's cover image.
     * @return A [Result] containing a success message or an [Exception] on failure.
     */
    suspend fun addDownloadedBook(bookFile: File, title: String, author: String, coverFile: File?): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (bookDao.isBookExists(title)) {
                return@withContext Result.failure(Exception("Book already exists"))
            }

            // 1. GENERATE HASH
            val bookHash = bookFile.inputStream().use { stream ->
                calculateMd5Hash(stream)
            }

            val newBook = BookEntity(
                title = title,
                author = author,
                // PLATFORM: Convert File to URI String safely
                uriString = platformHelper.getFileUriString(bookFile),

                // NEW SYNC FIELDS
                bookHash = bookHash,
                lastCfiLocation = null,

                coverPath = coverFile?.absolutePath,
                addedDate = System.currentTimeMillis(),
                lastRead = System.currentTimeMillis(),
                progress = 0.0
            )

            bookDao.insertBook(newBook)
            Result.success("Saved to Library")
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    /**
     * Calculates the MD5 hash of a given input stream.
     * This is used to create a unique identifier for a book file, which is crucial for
     * synchronization and duplicate detection, regardless of the file's name or location.
     * The method reads the stream in chunks to handle large files efficiently.
     *
     * @param stream The [InputStream] of the file to be hashed.
     * @return A hexadecimal [String] representation of the MD5 hash.
     */
    private fun calculateMd5Hash(stream: InputStream): String {
        val buffer = ByteArray(8192) // 8KB buffer
        val digest = MessageDigest.getInstance("MD5")
        var bytesRead: Int
        while (stream.read(buffer).also { bytesRead = it } != -1) {
            digest.update(buffer, 0, bytesRead)
        }
        val md5Bytes = digest.digest()

        // Convert byte array to Hex String
        return md5Bytes.joinToString("") { "%02x".format(it) }
    }
}