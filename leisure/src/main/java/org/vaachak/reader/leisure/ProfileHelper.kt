package org.vaachak.reader.leisure

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.vaachak.reader.core.domain.model.ProfileEntity
import org.vaachak.reader.leisure.ui.settings.ProfileViewModel
import androidx.compose.runtime.setValue

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton


@Composable
fun ProfilePickerScreen(
    isEink: Boolean,
    viewModel: ProfileViewModel,
    onProfileSelected: (String) -> Unit
) {
    val contentColor = if (isEink) Color.Black else MaterialTheme.colorScheme.onBackground
    val bgColor = if (isEink) Color.White else MaterialTheme.colorScheme.background

    // Reactively observe the database!
    val profiles by viewModel.profiles.collectAsState()
    val pinError by viewModel.pinError.collectAsState()

    // Navigation States (Replaces the Dialogs!)
    var showAddScreen by remember { mutableStateOf(false) }
    var profileRequiringPin by remember { mutableStateOf<ProfileEntity?>(null) }
    var enteredPin by remember { mutableStateOf("") }

    if (showAddScreen) {
        // --- FULL-SCREEN: ADD PROFILE ---
        // Completely covers the background to prevent e-ink ghosting
        var newName by remember { mutableStateOf("") }
        var newPin by remember { mutableStateOf("") }

        val generateRandomName = {
            val adjectives = listOf("Quiet", "Curious", "Sleepy", "Brave", "Clever", "Cosmic", "Wandering", "Lunar", "Mystic", "Nimble")
            val nouns = listOf("Reader", "Owl", "Fox", "Panda", "Badger", "Bear", "Cat", "Dragon", "Raven", "Wolf")
            newName = "${adjectives.random()} ${nouns.random()}"
        }

        Column(
            modifier = Modifier.fillMaxSize().background(bgColor).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Add Reader Profile", style = MaterialTheme.typography.headlineMedium, color = contentColor)
            Spacer(modifier = Modifier.height(32.dp))

            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(0.8f),
                // NEW: Random Name Generator Button!
                trailingIcon = {
                    IconButton(onClick = generateRandomName) {
                        Icon(Icons.Default.Refresh, "Generate Random Name", tint = contentColor)
                    }
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = newPin,
                onValueChange = { newPin = it },
                label = { Text("PIN (Optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(0.8f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                visualTransformation = PasswordVisualTransformation()
            )

            Spacer(modifier = Modifier.height(8.dp))
            Text("Leave PIN blank to login instantly.", style = MaterialTheme.typography.labelSmall, color = Color.Gray)

            Spacer(modifier = Modifier.height(32.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TextButton(onClick = { showAddScreen = false }) {
                    Text("Cancel", color = contentColor)
                }
                Button(
                    onClick = {
                        if (newName.isNotBlank()) {
                            viewModel.createProfile(name = newName.trim(), pin = newPin.ifBlank { null })
                            showAddScreen = false
                        }
                    }
                ) {
                    Text("Create")
                }
            }
        }

    } else if (profileRequiringPin != null) {
        // --- FULL-SCREEN: PIN ENTRY ---
        Column(
            modifier = Modifier.fillMaxSize().background(bgColor).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Welcome back, ${profileRequiringPin?.name}", style = MaterialTheme.typography.headlineMedium, color = contentColor)
            Spacer(modifier = Modifier.height(32.dp))

            OutlinedTextField(
                value = enteredPin,
                onValueChange = { enteredPin = it; viewModel.clearPinError() },
                label = { Text("Enter PIN") },
                singleLine = true,
                isError = pinError != null,
                modifier = Modifier.fillMaxWidth(0.8f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                visualTransformation = PasswordVisualTransformation()
            )

            if (pinError != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(pinError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
            }

            Spacer(modifier = Modifier.height(32.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TextButton(
                    onClick = {
                        profileRequiringPin = null
                        viewModel.clearPinError()
                    }
                ) { Text("Cancel", color = contentColor) }

                Button(
                    onClick = {
                        val success = viewModel.unlockProfile(profileRequiringPin!!, enteredPin)
                        if (success) {
                            val id = profileRequiringPin?.profileId ?: ""
                            profileRequiringPin = null
                            onProfileSelected(id)
                        }
                    }
                ) { Text("Unlock") }
            }
        }

    } else {
        // --- ORIGINAL: PROFILE PICKER ---
        Column(
            modifier = Modifier.fillMaxSize().background(bgColor),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Who's reading?", style = MaterialTheme.typography.headlineLarge, color = contentColor)
            Spacer(modifier = Modifier.height(48.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                profiles.forEach { profile ->
                    ProfileAvatar(
                        name = profile.name,
                        isEink = isEink,
                        onClick = {
                            if (profile.pinHash == null) {
                                if (viewModel.unlockProfile(profile, null)) {
                                    onProfileSelected(profile.profileId)
                                }
                            } else {
                                profileRequiringPin = profile
                                enteredPin = ""
                                viewModel.clearPinError()
                            }
                        }
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { showAddScreen = true }) {
                    Box(
                        modifier = Modifier.size(100.dp).background(if (isEink) Color.LightGray else Color.DarkGray, RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Add, "Add Profile", modifier = Modifier.size(48.dp), tint = contentColor)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Add Profile", style = MaterialTheme.typography.titleMedium, color = contentColor)
                }
            }
        }
    }
}

@Composable
fun ProfileAvatar(name: String, isEink: Boolean, onClick: () -> Unit) {
    val contentColor = if (isEink) Color.Black else MaterialTheme.colorScheme.onBackground
    val boxColor = if (isEink) Color.Black else MaterialTheme.colorScheme.primary
    val letterColor = if (isEink) Color.White else MaterialTheme.colorScheme.onPrimary

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick)) {
        Box(
            modifier = Modifier.size(100.dp).background(boxColor, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(name.take(1).uppercase(), style = MaterialTheme.typography.displayMedium, color = letterColor)
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(name, style = MaterialTheme.typography.titleMedium, color = contentColor)
    }
}