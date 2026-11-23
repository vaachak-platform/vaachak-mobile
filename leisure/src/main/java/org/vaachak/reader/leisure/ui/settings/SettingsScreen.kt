package org.vaachak.reader.leisure.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.SwitchAccount
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import org.vaachak.reader.core.domain.model.ComicPreferences
import org.vaachak.reader.core.domain.model.ComicPageFit
import org.vaachak.reader.core.domain.model.ComicReadingDirection
import org.vaachak.reader.core.domain.model.ComicReadingMode
import org.vaachak.reader.core.domain.model.ThemeMode
import org.vaachak.reader.leisure.navigation.Screen
import org.vaachak.reader.leisure.ui.testability.Tid
import org.vaachak.reader.leisure.ui.testability.TidScreen
import org.vaachak.reader.leisure.ui.testability.tid

/** No fixed tab widths: independent navigation and content panes work on e-ink tablets. */
private enum class SettingsCategory(
    val label: String,
    val description: String,
    val tag: String,
    val icon: ImageVector
) {
    GENERAL("General", "Profiles, appearance, network and sync", Tid.Settings.tabGlobal, Icons.Default.Person),
    BOOKS("Books & reading", "Book appearance, library and dictionary", Tid.Settings.tabBook, Icons.Default.Book),
    COMICS("Comics", "Layout, direction, navigation and e-ink", "settings_category_comics", Icons.Default.Image),
    AUDIO("Audio", "Speech and narration", "settings_category_audio", Icons.Default.Audiotrack),
    CONTENT("Library & content", "Catalog sources and imports", Tid.Settings.tabContent, Icons.AutoMirrored.Filled.List),
    INTELLIGENCE("Intelligence", "Gemini and online AI options", Tid.Settings.tabIntelligence, Icons.Default.AutoAwesome)
}

/** Data-only inputs let instrumented UI tests render real Settings without Hilt or storage. */
internal data class SettingsContentState(
    val settings: SettingsUiState,
    val activeVaultId: String,
    val isMultiUserMode: Boolean,
    val comics: ComicPreferences
)

/** Production and tests use the same UI callbacks. Secrets never enter UI state. */
internal data class SettingsUiActions(
    val saveComicPreferences: (ComicPreferences) -> Unit,
    val setMultiUserMode: (Boolean) -> Unit,
    val switchVault: (String) -> Unit,
    val toggleOfflineMode: (Boolean) -> Unit
)

@Composable
fun SettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val activeVaultId by viewModel.activeVaultId.collectAsState()
    val isMultiUserMode by viewModel.isMultiUserMode.collectAsState()
    val comicPreferences by viewModel.comicPreferences.collectAsState()
    SettingsScreenContent(
        model = SettingsContentState(state, activeVaultId, isMultiUserMode, comicPreferences),
        navController = navController,
        actions = SettingsUiActions(
            saveComicPreferences = { viewModel.saveComicPreferences(it) },
            setMultiUserMode = viewModel::setMultiUserMode,
            switchVault = viewModel::switchVault,
            toggleOfflineMode = viewModel::toggleOfflineMode
        )
    )
}

/** The exact production screen, parameterized only by already-present state and actions. */
@Composable
internal fun SettingsScreenContent(
    model: SettingsContentState,
    navController: NavController,
    actions: SettingsUiActions,
    modifier: Modifier = Modifier
) {
    val state = model.settings
    val activeVaultId = model.activeVaultId
    val isMultiUserMode = model.isMultiUserMode
    val comicPreferences = model.comics
    val eink = state.themeMode == ThemeMode.E_INK
    var selectedName by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = SettingsCategory.entries.firstOrNull { it.name == selectedName }

    TidScreen(Tid.Screen.settings) {
        BoxWithConstraints(modifier.fillMaxSize()) {
            val scale = LocalDensity.current.fontScale
            val twoPane = SettingsLayoutPolicy.useTwoPane(maxWidth.value, scale)
            BackHandler(enabled = !twoPane && selected != null) { selectedName = null }

            if (twoPane) {
                Row(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    SettingsCategoryChooser(
                        selected = selected ?: SettingsCategory.GENERAL,
                        onSelect = { selectedName = it.name },
                        eink = eink,
                        modifier = Modifier.width(288.dp).fillMaxHeight()
                    )
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .width(1.dp)
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.outline)
                    )
                    Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        SettingsDetail(
                            category = selected ?: SettingsCategory.GENERAL,
                            model = model,
                            actions = actions,
                            navController = navController,
                            modifier = Modifier.widthIn(max = 800.dp).fillMaxWidth()
                        )
                    }
                }
            } else if (selected == null) {
                SettingsCategoryChooser(
                    selected = null,
                    onSelect = { selectedName = it.name },
                    eink = eink,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { selectedName = null }, modifier = Modifier.size(56.dp)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "All settings")
                        }
                        Text(selected.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                    HorizontalDivider()
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        SettingsDetail(
                            category = selected,
                            model = model,
                            actions = actions,
                            navController = navController,
                            modifier = Modifier.widthIn(max = 800.dp).fillMaxWidth(),
                            showHeading = false
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsCategoryChooser(
    selected: SettingsCategory?,
    onSelect: (SettingsCategory) -> Unit,
    eink: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Choose a category. Preferences are stored for the active reader profile.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        SettingsCategory.entries.forEach { item ->
            val active = item == selected
            val shape = RoundedCornerShape(if (eink) 2.dp else 12.dp)
            val tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            val background = if (active) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .clip(shape)
                    .background(background)
                    .border(
                        width = if (active) 2.dp else 1.dp,
                        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        shape = shape
                    )
                    .clickable(role = Role.Button) { onSelect(item) }
                    .tid(item.tag)
                    .semantics(mergeDescendants = true) {
                        contentDescription = "Settings: ${item.label}${if (active) ", selected" else ""}"
                    }
                    .heightIn(min = 72.dp)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(item.icon, contentDescription = null, tint = tint)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.label, style = MaterialTheme.typography.titleMedium, color = tint)
                    Text(item.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!active) Icon(Icons.Default.ChevronRight, contentDescription = null)
            }
        }
    }
}

@Composable
private fun SettingsDetail(
    category: SettingsCategory,
    model: SettingsContentState,
    actions: SettingsUiActions,
    navController: NavController,
    modifier: Modifier = Modifier,
    showHeading: Boolean = true
) {
    // Category-specific scroll state resets when a reader switches sections.
    androidx.compose.runtime.key(category) {
        Column(
            modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (showHeading) {
                Text(category.label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            when (category) {
                SettingsCategory.GENERAL -> GlobalSettingsBody(model.settings, model.activeVaultId, model.isMultiUserMode, actions, navController)
                SettingsCategory.BOOKS -> BookSettingsTab(navController)
                SettingsCategory.COMICS -> ComicsSettingsTab(model.comics, onChange = actions.saveComicPreferences)
                SettingsCategory.AUDIO -> AudioSettingsTab(navController)
                SettingsCategory.CONTENT -> ContentSettingsTab(navController)
                SettingsCategory.INTELLIGENCE -> IntelligenceSettingsTab(model.settings, navController)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun ComicsSettingsTab(preferences: ComicPreferences, onChange: (ComicPreferences) -> Unit) {
    Text(
        "Comic reading preferences are saved now. They will be applied by the upcoming CBZ/CBR reader.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    SettingsGroup(title = "Reading direction") {
        ComicChoice("Left to right", preferences.readingDirection == ComicReadingDirection.LEFT_TO_RIGHT) {
            onChange(preferences.copy(readingDirection = ComicReadingDirection.LEFT_TO_RIGHT))
        }
        ComicChoice("Right to left (manga)", preferences.readingDirection == ComicReadingDirection.RIGHT_TO_LEFT) {
            onChange(preferences.copy(readingDirection = ComicReadingDirection.RIGHT_TO_LEFT))
        }
    }
    SettingsGroup(title = "Reading mode") {
        ComicChoice("One page at a time", preferences.readingMode == ComicReadingMode.SINGLE_PAGE) {
            onChange(preferences.copy(readingMode = ComicReadingMode.SINGLE_PAGE))
        }
        ComicChoice("Continuous vertical scroll", preferences.readingMode == ComicReadingMode.VERTICAL_SCROLL) {
            onChange(preferences.copy(readingMode = ComicReadingMode.VERTICAL_SCROLL))
        }
    }
    SettingsGroup(title = "Page scaling") {
        ComicChoice("Fit whole page", preferences.pageFit == ComicPageFit.FIT_PAGE) {
            onChange(preferences.copy(pageFit = ComicPageFit.FIT_PAGE))
        }
        ComicChoice("Fit page width", preferences.pageFit == ComicPageFit.FIT_WIDTH) {
            onChange(preferences.copy(pageFit = ComicPageFit.FIT_WIDTH))
        }
    }
    SettingsGroup(title = "Navigation & e-ink") {
        ComicSwitch("Tap edges to turn pages", preferences.tapToTurn) {
            onChange(preferences.copy(tapToTurn = it))
        }
        ComicSwitch("Use volume keys for page turns", preferences.useVolumeKeys) {
            onChange(preferences.copy(useVolumeKeys = it))
        }
        ComicSwitch("Preload next page", preferences.preloadNextPage) {
            onChange(preferences.copy(preloadNextPage = it))
        }
        ComicSwitch("Reduce motion for e-ink", preferences.reduceMotion) {
            onChange(preferences.copy(reduceMotion = it))
        }
    }
}

@Composable
private fun ComicChoice(title: String, checked: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = checked, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ComicSwitch(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .clickable(role = Role.Switch) { onChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun AudioSettingsTab(navController: NavController) {
    SettingsGroup(title = "Speech & narration") {
        SettingsTile(
            icon = Icons.Default.RecordVoiceOver,
            title = "Text-to-speech preferences",
            subtitle = "Voice, reading speed, pitch and sleep timer",
            onClick = { navController.navigate(Screen.TTS.route) }
        )
    }
    Text(
        "Audiobook playback settings will be added with the audiobook engine.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
fun GlobalSettingsTab(
    state: SettingsUiState,
    activeVaultId: String,
    isMultiUserMode: Boolean,
    viewModel: SettingsViewModel,
    navController: NavController
) {
    GlobalSettingsBody(
        state, activeVaultId, isMultiUserMode,
        SettingsUiActions(
            saveComicPreferences = { viewModel.saveComicPreferences(it) },
            setMultiUserMode = viewModel::setMultiUserMode,
            switchVault = viewModel::switchVault,
            toggleOfflineMode = viewModel::toggleOfflineMode
        ), navController
    )
}

@Composable
private fun GlobalSettingsBody(
    state: SettingsUiState,
    activeVaultId: String,
    isMultiUserMode: Boolean,
    actions: SettingsUiActions,
    navController: NavController
) {
    var showVaultDialog by remember { mutableStateOf(false) }

    SettingsGroup(title = "Account & Profiles") {
        SettingsTile(
            icon = Icons.Default.Person, title = "Reader Profiles", subtitle = "Manage local profiles and credentials",
            onClick = { navController.navigate("reader_profiles") }
        )

        SettingsTile(
            icon = Icons.Default.Group, title = "Local Multi-User Mode", subtitle = "Isolate libraries for different offline readers",
            onClick = { actions.setMultiUserMode(!isMultiUserMode) },
            trailing = { Switch(checked = isMultiUserMode, onCheckedChange = { actions.setMultiUserMode(it) }) }
        )

        if (isMultiUserMode) {
            SettingsTile(
                icon = Icons.Default.SwitchAccount, title = "Active Profile", subtitle = activeVaultId.replaceFirstChar { it.uppercase() },
                onClick = { showVaultDialog = true }
            )
        }

        SettingsTile(
            icon = Icons.Default.Sync, title = "Sync Configuration", subtitle = "Manage end-to-end encrypted vaults",
            onClick = { navController.navigate(Screen.SyncSettings.route) }
        )
    }

    SettingsGroup(title = "Device & Network") {
        SettingsTile(
            icon = if (state.isOfflineMode) Icons.Outlined.CloudOff else Icons.Default.CloudQueue,
            title = "Offline Mode", subtitle = "Default: On. Cloud features disabled.",
            onClick = { actions.toggleOfflineMode(!state.isOfflineMode) },
            trailing = { Switch(checked = state.isOfflineMode, onCheckedChange = { actions.toggleOfflineMode(it) }) }
        )
        SettingsTile(
            icon = Icons.Default.Contrast, title = "App-wide Theme", subtitle = "E-ink vs LCD, Sharpness",
            onClick = { navController.navigate("settings/app_appearance") }
        )
    }

    if (showVaultDialog) {
        if (state.userProfile?.isAuthenticated == true) {
            AlertDialog(
                onDismissRequest = { showVaultDialog = false },
                title = { Text("Cloud Account Active") },
                text = { Text("To switch local profiles safely, please log out of your current cloud account first to prevent mixing sync data.") },
                confirmButton = { TextButton(onClick = { showVaultDialog = false }) { Text("OK") } } // Maestro sees "OK"
            )
        } else {
            var newVaultName by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showVaultDialog = false },
                title = { Text("Switch Local Profile") },
                text = {
                    Column {
                        Text("Enter the name of the offline profile you want to switch to or create.")
                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(
                            value = newVaultName,
                            onValueChange = { newVaultName = it },
                            label = { Text("Profile Name") }, // Maestro reads this label
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (newVaultName.isNotBlank()) {
                            actions.switchVault(newVaultName)
                        }
                        showVaultDialog = false
                    }) { Text("Switch") } // Maestro sees "Switch"
                },
                dismissButton = {
                    TextButton(onClick = { showVaultDialog = false }) { Text("Cancel") }
                }
            )
        }
    }
}

@Composable
fun BookSettingsTab(navController: NavController) {
    SettingsGroup(title = "Library & Visuals") {
        SettingsTile(
            icon = Icons.AutoMirrored.Filled.LibraryBooks, title = "Library Settings (Visuals)", subtitle = "E-ink optimization, Cover Styles, Smart Stacks",
            onClick = { navController.navigate(Screen.Bookshelf.route) }
        )
    }

    SettingsGroup(title = "Reading Experience") {
        SettingsTile(
            icon = Icons.Default.FormatSize, title = "Default Book Appearance", subtitle = "Fonts, margins, publisher styles, page color",
            onClick = { navController.navigate("settings/appearance") }
        )
        SettingsTile(
            icon = Icons.Default.RecordVoiceOver, title = "Speech & Narration", subtitle = "Language, Speed, Pitch, Sleep-Timer",
            onClick = { navController.navigate("settings/tts") }
        )
        SettingsTile(
            icon = Icons.Default.Book, title = "Dictionary Settings", subtitle = "Manage local and custom dictionaries",
            onClick = { navController.navigate("settings/dictionary") }
        )
    }
}

@Composable
fun ContentSettingsTab(navController: NavController) {
    SettingsGroup(title = "Sources") {
        SettingsTile(
            icon = Icons.AutoMirrored.Filled.List, title = "Manage Catalogs", subtitle = "Add or remove OPDS feeds",
            onClick = { navController.navigate("catalog_manage") }
        )
    }
}

@Composable
fun IntelligenceSettingsTab(state: SettingsUiState, navController: NavController) {
    if (state.isOfflineMode) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                // --- MAESTRO FIX 3: Semantic merging for error cards ---
                .semantics(mergeDescendants = true) {
                    contentDescription = "Intelligence features are disabled while Offline Mode is active."
                },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        ) {
            Text("Intelligence features are disabled while Offline Mode is active.", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
        }
    } else {
        SettingsGroup(title = "AI Configuration") {
            SettingsTile(
                icon = Icons.Default.AutoAwesome, title = "Gemini Integration", subtitle = "Manage API keys for Global Recall and Smart Summaries",
                onClick = { navController.navigate(Screen.AiConfig.route) }
            )
            Text(
                "Cloudflare image configuration will be reviewed in a separate integration update.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}


@androidx.compose.ui.tooling.preview.Preview(name = "Phone · compact", widthDp = 393, heightDp = 850)
@Composable
private fun CompactSettingsPreview() {
    MaterialTheme {
        SettingsCategoryChooser(selected = null, onSelect = {}, eink = false)
    }
}

@androidx.compose.ui.tooling.preview.Preview(name = "BOOX · e-ink categories", widthDp = 288, heightDp = 760)
@Composable
private fun EinkSettingsCategoriesPreview() {
    MaterialTheme {
        SettingsCategoryChooser(selected = SettingsCategory.COMICS, onSelect = {}, eink = true)
    }
}
