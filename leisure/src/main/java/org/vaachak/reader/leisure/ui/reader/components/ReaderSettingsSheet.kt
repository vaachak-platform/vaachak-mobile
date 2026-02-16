package org.vaachak.reader.leisure.ui.reader.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.filled.FormatAlignJustify
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.Theme
import org.vaachak.reader.leisure.ui.reader.ReaderViewModel
import org.vaachak.reader.leisure.ui.settings.AlignmentOption
import org.vaachak.reader.leisure.ui.settings.CompactSettingsToggle
import org.vaachak.reader.leisure.ui.settings.CustomThemePreviewCard
import org.vaachak.reader.leisure.ui.settings.FontFamilyGrid
import org.vaachak.reader.leisure.ui.settings.LabelledSlider
import org.vaachak.reader.leisure.ui.settings.SettingsSectionTitle
import org.vaachak.reader.leisure.ui.settings.ThemeOption

// Explicit Aliases
typealias ReadiumFontFamily = org.readium.r2.navigator.preferences.FontFamily
typealias ReadiumTextAlign = org.readium.r2.navigator.preferences.TextAlign

// --- SHARED FONT MAPPING HELPER ---
private fun getComposeFontFamily(fontName: String): androidx.compose.ui.text.font.FontFamily {
    val name = fontName.lowercase()
    return when {
        name.contains("writer") -> org.vaachak.reader.leisure.ui.settings.IAWriterFamily
        name.contains("dyslexic") -> org.vaachak.reader.leisure.ui.settings.OpenDyslexicFamily
        name.contains("accessible") -> org.vaachak.reader.leisure.ui.settings.AccessibleDfaFamily
        name.contains("mono") -> org.vaachak.reader.leisure.ui.settings.MonospaceFamily
        name.contains("serif") && !name.contains("sans") -> androidx.compose.ui.text.font.FontFamily.Serif
        name.contains("cursive") -> androidx.compose.ui.text.font.FontFamily.Cursive
        name.contains("sans") -> androidx.compose.ui.text.font.FontFamily.SansSerif
        else -> androidx.compose.ui.text.font.FontFamily.Default
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderSettingsSheet(
    viewModel: ReaderViewModel,
    isEink: Boolean,
    onDismiss: () -> Unit
) {
    // 1. Live Data Sources
    val prefs by viewModel.epubPreferences.collectAsState()
    val isAiEnabled by viewModel.isAiEnabled.collectAsState()

    // 2. UI State
    val isPublisherStyles = prefs.publisherStyles ?: true
    val areSettingsEnabled = !isPublisherStyles
    var selectedTab by remember { mutableIntStateOf(0) }

    // 3. Theme Colors
    val containerColor = if (isEink) Color.White else MaterialTheme.colorScheme.background
    val contentColor = if (isEink) Color.Black else MaterialTheme.colorScheme.onBackground
    val primaryColor = if (isEink) Color.Black else MaterialTheme.colorScheme.primary

    // Helper to update prefs immediately
    fun update(newPrefs: EpubPreferences) {
        viewModel.savePreferences(newPrefs)
    }

    // --- FULL SCREEN SURFACE ---
    // Fix: Explicit background and elevation to prevent scrolling artifacts (blurring)
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(containerColor),
        color = containerColor,
        contentColor = contentColor,
        shadowElevation = 0.dp
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // --- HEADER ---
            TopAppBar(
                title = { Text("Reader Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = contentColor
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = containerColor,
                    titleContentColor = contentColor,
                    navigationIconContentColor = contentColor
                )
            )

            // --- GLOBAL TOGGLES ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CompactSettingsToggle(
                    title = "Enable AI",
                    subtitle = "Smart features",
                    checked = isAiEnabled,
                    primaryColor = primaryColor,
                    isEink = isEink,
                    onCheckedChange = { viewModel.toggleBookAi(it) },
                    modifier = Modifier.weight(1f)
                )

                CompactSettingsToggle(
                    title = "Publisher Styles",
                    subtitle = "Original layout",
                    checked = isPublisherStyles,
                    primaryColor = primaryColor,
                    isEink = isEink,
                    onCheckedChange = { update(prefs.copy(publisherStyles = it)) },
                    modifier = Modifier.weight(1f)
                )
            }

            HorizontalDivider(color = Color.Gray.copy(alpha = 0.1f))

            // --- TABS ---
            Box(modifier = Modifier.alpha(if (areSettingsEnabled) 1f else 0.5f)) {
                SecondaryTabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = containerColor,
                    contentColor = primaryColor,
                    divider = { HorizontalDivider(color = Color.Gray.copy(alpha = 0.1f)) }
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { if (areSettingsEnabled) selectedTab = 0 },
                        text = { Text("Display", fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal) },
                        enabled = areSettingsEnabled,
                        selectedContentColor = primaryColor,
                        unselectedContentColor = contentColor.copy(alpha = 0.6f)
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { if (areSettingsEnabled) selectedTab = 1 },
                        text = { Text("Layout", fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal) },
                        enabled = areSettingsEnabled,
                        selectedContentColor = primaryColor,
                        unselectedContentColor = contentColor.copy(alpha = 0.6f)
                    )
                }
            }

            // --- SCROLLABLE CONTENT ---
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp)
                    .alpha(if (areSettingsEnabled) 1f else 0.38f),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                contentPadding = PaddingValues(top = 24.dp, bottom = 48.dp)
            ) {
                if (selectedTab == 0) {
                    // ================= DISPLAY TAB =================

                    item {
                        SettingsSectionTitle("Preview")
                        CustomThemePreviewCard(
                            themeStr = prefs.theme?.name?.lowercase() ?: "light",
                            fontName = prefs.fontFamily?.name ?: "Original",
                            fontSize = prefs.fontSize ?: 1.0,
                            lineHeight = prefs.lineHeight ?: 1.2
                        )
                    }

                    item {
                        SettingsSectionTitle("Page Color")
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            val currentTheme = prefs.theme ?: Theme.LIGHT
                            ThemeOption("Light", Color.White, Color.Black, currentTheme == Theme.LIGHT, areSettingsEnabled) {
                                update(prefs.copy(theme = Theme.LIGHT))
                            }
                            ThemeOption("Dark", Color(0xFF121212), Color.White, currentTheme == Theme.DARK, areSettingsEnabled) {
                                update(prefs.copy(theme = Theme.DARK))
                            }
                            ThemeOption("Sepia", Color(0xFFF5E6D3), Color(0xFF5F4B32), currentTheme == Theme.SEPIA, areSettingsEnabled) {
                                update(prefs.copy(theme = Theme.SEPIA))
                            }
                        }
                    }

                    item {
                        SettingsSectionTitle("Font Family")
                        FontFamilyGrid(
                            currentFamily = prefs.fontFamily,

                            enabled = areSettingsEnabled
                        ) { family ->
                            update(prefs.copy(fontFamily = family))
                        }
                    }

                    item {
                        LabelledSlider(
                            label = "Base Font Size",
                            value = (prefs.fontSize ?: 1.0).toFloat(),
                            range = 0.5f..2.5f,
                            unit = "x",
                            activeColor = primaryColor,
                            enabled = areSettingsEnabled,
                            defaultVisualValue = 1.0f
                        ) {
                            update(prefs.copy(fontSize = it.toDouble()))
                        }
                    }

                } else {
                    // ================= LAYOUT TAB =================

                    item {
                        SettingsSectionTitle("Paragraph Preview")
                        LayoutPreviewCard(
                            themeStr = prefs.theme?.name?.lowercase() ?: "light",
                            fontName = prefs.fontFamily?.name ?: "Original",
                            fontSize = prefs.fontSize ?: 1.0,
                            textAlignStr = prefs.textAlign?.name ?: "START",
                            lineHeight = prefs.lineHeight ?: 1.2,
                            paraSpacing = prefs.paragraphSpacing ?: 0.5,
                            marginScale = prefs.pageMargins ?: 1.0,
                            letterSpacing = prefs.letterSpacing ?: 0.1
                        )
                    }

                    item {
                        SettingsSectionTitle("Text Alignment")
                        val align = prefs.textAlign ?: ReadiumTextAlign.START
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            AlignmentOption(Icons.AutoMirrored.Filled.FormatAlignLeft, "Auto", align == ReadiumTextAlign.START, primaryColor, areSettingsEnabled) {
                                update(prefs.copy(textAlign = ReadiumTextAlign.START))
                            }
                            AlignmentOption(Icons.AutoMirrored.Filled.FormatAlignLeft, "Left", align == ReadiumTextAlign.LEFT, primaryColor, areSettingsEnabled) {
                                update(prefs.copy(textAlign = ReadiumTextAlign.LEFT))
                            }
                            AlignmentOption(Icons.Default.FormatAlignJustify, "Justify", align == ReadiumTextAlign.JUSTIFY, primaryColor, areSettingsEnabled) {
                                update(prefs.copy(textAlign = ReadiumTextAlign.JUSTIFY))
                            }
                        }
                    }

                    item {
                        SettingsSectionTitle("Spacing & Margins")

                        // LINE HEIGHT
                        LabelledSlider(
                            label = "Line Height",
                            value = prefs.lineHeight?.toFloat(), // Pass Nullable
                            defaultVisualValue = 1.2f,           // Visual fallback position
                            range = 1.0f..2.5f,
                            unit = "x",
                            activeColor = primaryColor,
                            enabled = areSettingsEnabled
                        ) {
                            update(prefs.copy(lineHeight = it.toDouble()))
                        }

                        Spacer(Modifier.height(16.dp))

                        // PARAGRAPH GAP
                        LabelledSlider(
                            label = "Paragraph Gap",
                            value = prefs.paragraphSpacing?.toFloat(), // Pass Nullable
                            defaultVisualValue = 0.5f,
                            range = 0f..2.0f,
                            unit = "em",
                            activeColor = primaryColor,
                            enabled = areSettingsEnabled
                        ) {
                            update(prefs.copy(paragraphSpacing = it.toDouble()))
                        }
                        // PARAGRAPH GAP
                        LabelledSlider(
                            label = "Letter Spacing",
                            value = prefs.letterSpacing?.toFloat(), // Pass Nullable
                            defaultVisualValue = 0.1f,
                            range = 0f..0.5f,
                            unit = "em",
                            activeColor = primaryColor,
                            enabled = areSettingsEnabled
                        ) {
                            update(prefs.copy(letterSpacing = it.toDouble()))
                        }
                        Spacer(Modifier.height(16.dp))

                        // MARGINS
                        LabelledSlider(
                            label = "Side Margins",
                            value = prefs.pageMargins?.toFloat(), // Pass Nullable
                            defaultVisualValue = 1.0f,
                            range = 0.5f..3.0f,
                            unit = "x",
                            activeColor = primaryColor,
                            enabled = areSettingsEnabled
                        ) {
                            update(prefs.copy(pageMargins = it.toDouble()))
                        }
                    }

                    item {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                viewModel.resetLayoutToDefaults()
                            },
                            enabled = areSettingsEnabled,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Reset Layout to Defaults", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

// --- RESTORED LAYOUT PREVIEW CARD ---
@Composable
fun LayoutPreviewCard(
    themeStr: String,
    fontName: String,
    fontSize: Double,
    textAlignStr: String,
    lineHeight: Double,
    paraSpacing: Double,
    marginScale: Double,
    letterSpacing: Double
) {
    val theme = when(themeStr) { "dark" -> Theme.DARK; "sepia" -> Theme.SEPIA; else -> Theme.LIGHT }
    val (bg, fg) = when(theme) {
        Theme.DARK -> Color(0xFF121212) to Color(0xFFE0E0E0)
        Theme.SEPIA -> Color(0xFFF5E6D3) to Color(0xFF5F4B32)
        else -> Color.White to Color.Black
    }

    val textAlign = when(textAlignStr) {
        "JUSTIFY" -> TextAlign.Justify
        "LEFT" -> TextAlign.Left
        else -> TextAlign.Start
    }

    val fontFamily = getComposeFontFamily(fontName)
    val sidePadding = (16 * marginScale).coerceAtMost(48.0).dp

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = bg),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier
                .padding(vertical = 16.dp)
                .padding(horizontal = sidePadding)
        ) {
            Text(
                text = "This paragraph demonstrates the alignment setting. Watch how the edges of the text change.",
                color = fg,
                fontSize = (14 * fontSize).sp,
                lineHeight = (14 * fontSize * lineHeight).sp,
                fontFamily = fontFamily,
                textAlign = textAlign,
                letterSpacing= (letterSpacing * fontSize).sp
            )

            Spacer(modifier = Modifier.height((16 * paraSpacing).dp))

            Text(
                text = "The gap above this line is controlled by Paragraph Spacing. Increasing it makes reading long chapters easier.",
                color = fg,
                fontSize = (14 * fontSize).sp,
                lineHeight = (14 * fontSize * lineHeight).sp,
                fontFamily = fontFamily,
                textAlign = textAlign,
                letterSpacing= (letterSpacing * fontSize).sp
            )
        }
    }
}