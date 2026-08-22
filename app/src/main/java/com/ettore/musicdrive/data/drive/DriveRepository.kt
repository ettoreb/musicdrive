package com.ettore.musicdrive.data.drive

import com.ettore.musicdrive.auth.DriveTokenProvider
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File as DriveFile
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DriveAudioFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long?,
)

private fun DriveFile.toDriveAudioFile() = DriveAudioFile(
    id = id,
    name = name,
    mimeType = mimeType,
    sizeBytes = getSize(),
)

class DriveRepository(private val tokenProvider: DriveTokenProvider) {

    private val transport = NetHttpTransport()
    private val jsonFactory = GsonFactory.getDefaultInstance()

    private suspend fun buildClient(token: String): Drive {
        val requestInitializer = HttpRequestInitializer { request ->
            request.headers.authorization = "Bearer $token"
        }
        return Drive.Builder(transport, jsonFactory, requestInitializer)
            .setApplicationName("MusicDrive")
            .build()
    }

    /** Smoke test: lists audio files visible to the signed-in account. */
    suspend fun listAudioFiles(): Result<List<DriveAudioFile>> = withContext(Dispatchers.IO) {
        val token = tokenProvider.getAccessToken().getOrElse {
            return@withContext Result.failure(it)
        }
        try {
            val drive = buildClient(token)
            val response = drive.files().list()
                .setQ("mimeType contains 'audio/' and trashed = false")
                .setFields("files(id, name, mimeType, size)")
                .setPageSize(100)
                .execute()
            Result.success(response.files.orEmpty().map { it.toDriveAudioFile() })
        } catch (e: GoogleJsonResponseException) {
            if (e.statusCode == 401) {
                tokenProvider.invalidate()
            }
            Result.failure(e)
        } catch (e: IOException) {
            Result.failure(e)
        }
    }
}
