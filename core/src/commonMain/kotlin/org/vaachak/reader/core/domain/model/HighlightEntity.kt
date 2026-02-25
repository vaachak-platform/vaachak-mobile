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
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import org.vaachak.reader.core.utils.generateUuid
import org.vaachak.reader.core.utils.getCurrentTimeMillis

@Serializable
@Entity(
    tableName = "highlights",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["bookHash"],
            childColumns = ["bookHashId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["bookHashId"])]
)
data class HighlightEntity(
    @PrimaryKey val id: String = generateUuid(), // Using KMP function
    val bookHashId: String,
    val locatorJson: String,
    val text: String,
    val color: Int,
    val tag: String = "General",
    val created: Long = getCurrentTimeMillis(), // Using KMP function
    val updatedAt: Long = getCurrentTimeMillis(), // Using KMP function
    val isDeleted: Boolean = false,
    val isDirty: Boolean = true
)