package com.ettore.musicdrive.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ettore.musicdrive.data.drive.DRIVE_ROOT_FOLDER_ID
import com.ettore.musicdrive.data.drive.DriveFolder
import com.ettore.musicdrive.data.drive.DriveRepository

private data class PathEntry(val id: String, val name: String)

/**
 * Lets the user browse their Drive and pick the folder to use as the music
 * library root. Subfolders of the chosen folder are treated as albums.
 */
@Composable
fun FolderPickerScreen(
    driveRepository: DriveRepository,
    onFolderSelected: (folderId: String, folderName: String) -> Unit,
    onCancel: (() -> Unit)? = null,
) {
    var path by remember { mutableStateOf(listOf(PathEntry(DRIVE_ROOT_FOLDER_ID, "My Drive"))) }
    var folders by remember { mutableStateOf<List<DriveFolder>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    val currentFolder = path.last()

    // Swiping/pressing back should step up one folder level, same as the "Up" button,
    // instead of falling through to the system default (which would exit the app).
    BackHandler(enabled = path.size > 1) { path = path.dropLast(1) }

    LaunchedEffect(currentFolder.id) {
        isLoading = true
        error = null
        driveRepository.listFolders(currentFolder.id).fold(
            onSuccess = { folders = it },
            onFailure = { error = it.message ?: "Failed to load folders" },
        )
        isLoading = false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (path.size > 1) {
                TextButton(onClick = { path = path.dropLast(1) }) {
                    Text("Up")
                }
            }
            Text(currentFolder.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            // Only reachable now that this screen can be entered from Settings (changing the
            // Drive folder, or picking Google Drive as the cloud provider) rather than only on
            // first run - a way to back out without having to pick a folder is needed there.
            if (onCancel != null) {
                TextButton(onClick = onCancel) {
                    Text("Cancel")
                }
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                error != null -> Text(
                    "Error: $error",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                )
                folders.isEmpty() -> Text(
                    "No subfolders here.",
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(folders) { folder ->
                        Text(
                            folder.name,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { path = path + PathEntry(folder.id, folder.name) }
                                .padding(16.dp),
                        )
                    }
                }
            }
        }

        Button(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            onClick = { onFolderSelected(currentFolder.id, currentFolder.name) },
        ) {
            Text("Use \"${currentFolder.name}\" as library folder")
        }
    }
}
