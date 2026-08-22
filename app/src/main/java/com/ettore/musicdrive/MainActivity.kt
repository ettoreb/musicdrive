package com.ettore.musicdrive

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ettore.musicdrive.auth.DriveAuthorizationManager
import com.ettore.musicdrive.auth.DriveTokenProvider
import com.ettore.musicdrive.auth.GoogleSignInManager
import com.ettore.musicdrive.auth.SignInResult
import com.ettore.musicdrive.data.drive.DriveAudioFile
import com.ettore.musicdrive.data.drive.DriveRepository
import com.ettore.musicdrive.ui.theme.MusicDriveTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var signInManager: GoogleSignInManager
    private lateinit var driveAuthorizationManager: DriveAuthorizationManager
    private lateinit var driveRepository: DriveRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        signInManager = GoogleSignInManager(this)
        driveAuthorizationManager = DriveAuthorizationManager(this)
        driveRepository = DriveRepository(DriveTokenProvider(driveAuthorizationManager))

        enableEdgeToEdge()
        setContent {
            MusicDriveTheme {
                MusicDriveApp(
                    signInManager = signInManager,
                    driveRepository = driveRepository,
                )
            }
        }
    }
}

private sealed class SmokeTestState {
    data object SignedOut : SmokeTestState()
    data object Loading : SmokeTestState()
    data class Loaded(val files: List<DriveAudioFile>) : SmokeTestState()
    data class Error(val message: String) : SmokeTestState()
}

@Composable
private fun MusicDriveApp(
    signInManager: GoogleSignInManager,
    driveRepository: DriveRepository,
) {
    var state by remember { mutableStateOf<SmokeTestState>(SmokeTestState.SignedOut) }
    val scope = rememberCoroutineScope()

    fun signInAndListAudioFiles() {
        state = SmokeTestState.Loading
        scope.launch {
            when (val signIn = signInManager.signIn()) {
                is SignInResult.Success -> {
                    driveRepository.listAudioFiles().fold(
                        onSuccess = { files -> state = SmokeTestState.Loaded(files) },
                        onFailure = { e -> state = SmokeTestState.Error(e.message ?: "Failed to list Drive files") },
                    )
                }
                is SignInResult.Failure -> {
                    state = SmokeTestState.Error(signIn.message)
                }
            }
        }
    }

    Scaffold { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (val current = state) {
                is SmokeTestState.SignedOut -> Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("MusicDrive")
                    Button(
                        modifier = Modifier.padding(top = 16.dp),
                        onClick = { signInAndListAudioFiles() },
                    ) {
                        Text("Sign in with Google")
                    }
                }

                is SmokeTestState.Loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

                is SmokeTestState.Error -> Column(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("Error: ${current.message}", color = MaterialTheme.colorScheme.error)
                    Button(
                        modifier = Modifier.padding(top = 16.dp),
                        onClick = { signInAndListAudioFiles() },
                    ) {
                        Text("Retry")
                    }
                }

                is SmokeTestState.Loaded -> if (current.files.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Signed in. No audio files found on Drive.")
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                        items(current.files) { file ->
                            Text("${file.name} (${file.mimeType})")
                        }
                    }
                }
            }
        }
    }
}
