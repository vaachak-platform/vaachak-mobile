package org.vaachak.reader.leisure.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.readium.r2.navigator.preferences.FontFamily

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DefaultAppearanceScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val readerPrefs by viewModel.readerPreferences.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()

    // Retrieve the active primary color from the theme to pass to components
    val primaryColor = MaterialTheme.colorScheme.primary

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Book Appearance") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {

            // --- PUBLISHER STYLES TOGGLE ---
            // Replaced manual Row/Switch with the shared CompactSettingsToggle
            CompactSettingsToggle(
                title = "Publisher Styles",
                subtitle = if (readerPrefs.publisherStyles) "Using original book layout" else "Custom fonts & layout enabled",
                checked = readerPrefs.publisherStyles,
                primaryColor = primaryColor,
                isEink = false, // Assuming standard screen here, or pass valid e-ink state if available
                onCheckedChange = {
                    viewModel.updateReaderPreferences(readerPrefs.copy(publisherStyles = it))
                }
            )

            if (!readerPrefs.publisherStyles) {

                // --- PREVIEW ---
                SettingsSectionTitle("Preview")

                CustomThemePreviewCard(
                    themeStr = readerPrefs.theme,
                    fontName = readerPrefs.fontFamily ?: "Original",
                    fontSize = readerPrefs.fontSize,
                    lineHeight = readerPrefs.lineHeight ?: 1.2
                )

                // --- FONTS ---
                SettingsSectionTitle("Font Family")

                val currentFont = if (readerPrefs.fontFamily == null || readerPrefs.fontFamily == "Original") null else FontFamily(readerPrefs.fontFamily!!)

                FontFamilyGrid(
                    currentFamily = currentFont,
                    enabled = true
                ) { newFamily ->
                    val name = newFamily?.name ?: "Original"
                    viewModel.updateReaderPreferences(readerPrefs.copy(fontFamily = name))
                }

                // --- SIZING ---
                // Base Font Size
                // Using the nullable LabelledSlider allows us to maintain consistent styling,
                // even though fontSize is technically non-nullable in the preference model.
                LabelledSlider(
                    label = "Base Font Size",
                    value = readerPrefs.fontSize.toFloat(),
                    defaultVisualValue = 1.0f,
                    range = 0.5f..2.5f,
                    unit = "x",
                    activeColor = primaryColor,
                    enabled = true
                ) { newVal ->
                    viewModel.updateReaderPreferences(readerPrefs.copy(fontSize = newVal.toDouble()))
                }

                // Line Height
                // Passes nullable value to support "Auto" state
                LabelledSlider(
                    label = "Line Height",
                    value = readerPrefs.lineHeight?.toFloat(),
                    defaultVisualValue = 1.2f,
                    range = 1.0f..2.5f,
                    unit = "",
                    activeColor = primaryColor,
                    enabled = true
                ) { newVal ->
                    viewModel.updateReaderPreferences(readerPrefs.copy(lineHeight = newVal.toDouble()))
                }

                // --- PAGE COLOR ---
                SettingsSectionTitle("Page Color")

                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ThemeOption("Light", Color.White, Color.Black, readerPrefs.theme == "light", true) {
                        viewModel.updateReaderPreferences(readerPrefs.copy(theme = "light"))
                    }
                    ThemeOption("Dark", Color(0xFF121212), Color.White, readerPrefs.theme == "dark", true) {
                        viewModel.updateReaderPreferences(readerPrefs.copy(theme = "dark"))
                    }
                    ThemeOption("Sepia", Color(0xFFF5E6D3), Color(0xFF5F4B32), readerPrefs.theme == "sepia", true) {
                        viewModel.updateReaderPreferences(readerPrefs.copy(theme = "sepia"))
                    }
                }
            } else {
                // Info card when styles are disabled
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Turn off Publisher Styles above to customize fonts, size, and spacing.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(16.dp),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}