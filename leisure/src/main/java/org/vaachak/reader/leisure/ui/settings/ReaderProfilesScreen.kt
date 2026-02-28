package org.vaachak.reader.leisure.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import org.vaachak.reader.core.domain.model.ProfileEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderProfilesScreen(
    navController: NavController,
    viewModel: ProfileViewModel = hiltViewModel()
) {
    // Reactively load profiles from Room!
    val profiles by viewModel.profiles.collectAsState()
    val pinError by viewModel.pinError.collectAsState()

    // State for the full-screen PIN change overlay
    var profileToEdit by remember { mutableStateOf<ProfileEntity?>(null) }
    var oldPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Manage Profiles") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->

        if (profileToEdit != null) {
            // --- FULL-SCREEN: CHANGE PIN FORM ---
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Change PIN for ${profileToEdit?.name}", style = MaterialTheme.typography.headlineMedium)
                Spacer(modifier = Modifier.height(32.dp))

                // Only ask for Old PIN if they actually have one set
                if (profileToEdit?.pinHash != null) {
                    OutlinedTextField(
                        value = oldPin,
                        onValueChange = { oldPin = it; viewModel.clearPinError() },
                        label = { Text("Current PIN") },
                        singleLine = true,
                        isError = pinError != null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        visualTransformation = PasswordVisualTransformation()
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }

                OutlinedTextField(
                    value = newPin,
                    onValueChange = { newPin = it; viewModel.clearPinError() },
                    label = { Text("New PIN (Optional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation()
                )

                Spacer(modifier = Modifier.height(8.dp))
                Text("Leave New PIN blank to remove password.", style = MaterialTheme.typography.labelSmall, color = Color.Gray)

                if (pinError != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(pinError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                }

                Spacer(modifier = Modifier.height(32.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TextButton(onClick = {
                        profileToEdit = null
                        viewModel.clearPinError()
                    }) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = {
                            val success = viewModel.changePin(profileToEdit!!, oldPin, newPin)
                            if (success) {
                                profileToEdit = null
                            }
                        }
                    ) {
                        Text("Save Changes")
                    }
                }
            }
        } else {
            // --- MAIN LIST: SHOW PROFILES ---
            if (profiles.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No profiles found", color = Color.Gray)
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(profiles) { profile ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Avatar Box
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = profile.name.take(1).uppercase(),
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                Spacer(modifier = Modifier.width(16.dp))

                                // Name
                                Text(
                                    text = profile.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.weight(1f)
                                )

                                // Change PIN Action
                                IconButton(
                                    onClick = {
                                        oldPin = ""
                                        newPin = ""
                                        viewModel.clearPinError()
                                        profileToEdit = profile
                                    }
                                ) {
                                    val icon = if (profile.pinHash != null) Icons.Default.Lock else Icons.Default.LockOpen
                                    Icon(icon, contentDescription = "Change PIN")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}