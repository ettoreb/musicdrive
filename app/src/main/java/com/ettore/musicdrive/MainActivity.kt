package com.ettore.musicdrive

import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.ettore.musicdrive.auth.DriveAuthorizationManager
import com.ettore.musicdrive.auth.DriveTokenProvider
import com.ettore.musicdrive.auth.GoogleSignInManager
import com.ettore.musicdrive.auth.SignInResult
import com.ettore.musicdrive.data.drive.DriveAudioFile
import com.ettore.musicdrive.data.drive.DriveRepository
import com.ettore.musicdrive.playback.MusicPlaybackService
import com.ettore.musicdrive.ui.theme.MusicDriveTheme
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.launch

@UnstableApi
class MainActivity : ComponentActivity() {

    private lateinit var signInManager: GoogleSignInManager
    private lateinit var driveAuthorizationManager: DriveAuthorizationManager
    private lateinit var driveRepository: DriveRepository
    private lateinit var controllerFuture: ListenableFuture<MediaController>
    private var mediaController: MediaController? = null

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33) {
            requestNotificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        signInManager = GoogleSignInManager(this)
        driveAuthorizationManager = DriveAuthorizationManager(this)
        val tokenProvider = DriveTokenProvider(driveAuthorizationManager)
        (application as MusicDriveApplication).driveTokenProvider = tokenProvider
        driveRepository = DriveRepository(tokenProvider)

        val sessionToken = SessionToken(this, ComponentName(this, MusicPlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture.addListener(
            { mediaController = controllerFuture.get() },
            MoreExecutors.directExecutor(),
        )

        enableEdgeToEdge()
        setContent {
            MusicDriveTheme {
                MusicDriveApp(
                    signInManager = signInManager,
                    driveRepository = driveRepository,
                    onPlayFile = ::playFile,
                )
            }
        }
    }

    private fun playFile(file: DriveAudioFile) {
        val controller = mediaController ?: return
        val mediaItem = MediaItem.Builder()
            .setMediaId(file.id)
            .setUri("https://www.googleapis.com/drive/v3/files/${file.id}?alt=media")
            .build()
        controller.setMediaItem(mediaItem)
        controller.prepare()
        controller.play()
    }

    override fun onDestroy() {
        MediaController.releaseFuture(controllerFuture)
        super.onDestroy()
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
    onPlayFile: (DriveAudioFile) -> Unit,
) {
    var state by remember { mutableStateOf<SmokeTestState>(SmokeTestState.Loading) }
    val scope = rememberCoroutineScope()

    fun signInAndListAudioFiles(interactive: Boolean) {
        state = SmokeTestState.Loading
        scope.launch {
            val signIn = if (interactive) signInManager.signInInteractive() else signInManager.signInSilently()
            when (signIn) {
                is SignInResult.Success -> {
                    driveRepository.listAudioFiles().fold(
                        onSuccess = { files -> state = SmokeTestState.Loaded(files) },
                        onFailure = { e -> state = SmokeTestState.Error(e.message ?: "Failed to list Drive files") },
                    )
                }
                is SignInResult.NoCredential -> {
                    state = SmokeTestState.SignedOut
                }
                is SignInResult.Failure -> {
                    state = SmokeTestState.Error(signIn.message)
                }
            }
        }
    }

    // Try to resume a previously authorized account silently before ever showing the sign-in button.
    LaunchedEffect(Unit) {
        signInAndListAudioFiles(interactive = false)
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
                        onClick = { signInAndListAudioFiles(interactive = true) },
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
                        onClick = { signInAndListAudioFiles(interactive = true) },
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
                            Text(
                                "${file.name} (${file.mimeType})",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPlayFile(file) }
                                    .padding(vertical = 12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
