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

package org.vaachak.reader.core.domain.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import org.vaachak.reader.core.utils.getCurrentTimeMillis

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val bookHash: String, // MD5/SHA256 of the EPUB (Universal cross-platform ID)
    val title: String,
    val author: String,

    // UI Grouping Fields (New)
    val seriesName: String? = null,
    val language: String? = "en", // e.g., "en", "gu"

    // Local Device File Pointer (Can be null if synced but not downloaded yet)
    val localUri: String? = null,
    val coverPath: String? = null,

    // Reading State
    val progress: Double = 0.0,
    val lastCfiLocation: String? = null,
    val addedDate: Long = getCurrentTimeMillis(),
    val lastRead: Long = getCurrentTimeMillis(),
    val lastLocationJson: String? = null,

    // Sync Triggers
    val updatedAt: Long = getCurrentTimeMillis(),
    val isDirty: Boolean = true // True if local changes haven't been pushed to the vault
)