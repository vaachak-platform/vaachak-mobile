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

package org.vaachak.reader.leisure.ui.reader.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun ReaderTopBar(
    bookTitle: String,
    isEink: Boolean,
    showRecap: Boolean,
    isBookmarked: Boolean,
    isTtsActive: Boolean,
    onBack: () -> Unit,
    onTocClick: () -> Unit,
    onSearchClick: () -> Unit,
    onHighlightsClick: () -> Unit,
    onBookmarksListClick: () -> Unit,
    onBookmarkToggleClick: () -> Unit,
    onRecapClick: () -> Unit,
    onTtsClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    val containerColor = if (isEink) Color.White else MaterialTheme.colorScheme.surface
    val contentColor = if (isEink) Color.Black else MaterialTheme.colorScheme.onSurface
    val dividerColor = if (isEink) Color.Black else MaterialTheme.colorScheme.outlineVariant

    var showMenu by remember { mutableStateOf(false) }

    Surface(
        color = containerColor,
        contentColor = contentColor,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = contentColor)
                }

                Text(
                    text = bookTitle,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = if (isEink) FontWeight.Bold else FontWeight.Medium
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                )

                IconButton(onClick = onTtsClick) {
                    Icon(
                        imageVector = if (isTtsActive) Icons.Default.PauseCircle else Icons.Default.Headphones,
                        contentDescription = "Read Aloud",
                        tint = if (isTtsActive && !isEink) MaterialTheme.colorScheme.primary else contentColor
                    )
                }

                IconButton(onClick = onBookmarkToggleClick) {
                    Icon(
                        imageVector = if (isBookmarked) Icons.Default.Bookmark else Icons.Outlined.BookmarkBorder,
                        contentDescription = "Bookmark",
                        tint = if (isBookmarked && !isEink) MaterialTheme.colorScheme.primary else contentColor
                    )
                }

                IconButton(onClick = onTocClick) {
                    Icon(Icons.AutoMirrored.Filled.List, "Table of Contents", tint = contentColor)
                }

                IconButton(onClick = onBookmarksListClick) {
                    Icon(Icons.Default.Bookmarks, "Bookmarks List", tint = contentColor)
                }

                IconButton(onClick = onHighlightsClick) {
                    Icon(Icons.Default.Edit, "Highlights", tint = contentColor)
                }

                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, "More Options", tint = contentColor)
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        containerColor = containerColor
                    ) {
                        DropdownMenuItem(
                            text = { Text("Search") },
                            leadingIcon = { Icon(Icons.Default.Search, null) },
                            onClick = { showMenu = false; onSearchClick() }
                        )
                        if (showRecap) {
                            DropdownMenuItem(
                                text = { Text("Recap") },
                                leadingIcon = { Icon(Icons.Default.History, null) },
                                onClick = { showMenu = false; onRecapClick() }
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Settings") },
                            leadingIcon = { Icon(Icons.Default.Settings, null) },
                            onClick = { showMenu = false; onSettingsClick() }
                        )
                    }
                }
            }
            HorizontalDivider(thickness = if (isEink) 1.dp else 0.5.dp, color = dividerColor)
        }
    }
}