package com.ettore.musicdrive.download

import android.app.Notification
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import androidx.media3.exoplayer.scheduler.Scheduler
import com.ettore.musicdrive.MusicDriveApplication
import com.ettore.musicdrive.R

private const val JOB_ID = 1
private const val FOREGROUND_NOTIFICATION_ID = 2
private const val CHANNEL_ID = "download_channel"

/**
 * DownloadManager (owned by MusicDriveApplication, so it - and the queue it
 * persists - outlives any one instance of this service) needs a foreground
 * DownloadService to run downloads while the app isn't in the foreground.
 * PlatformScheduler re-launches this service via JobScheduler if the process
 * dies mid-download and requirements (e.g. network back) are later met.
 */
@UnstableApi
class MusicDownloadService : DownloadService(
    FOREGROUND_NOTIFICATION_ID,
    DownloadService.DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID,
    R.string.download_channel_name,
    0,
) {
    private val notificationHelper: DownloadNotificationHelper by lazy {
        DownloadNotificationHelper(this, CHANNEL_ID)
    }

    override fun getDownloadManager(): DownloadManager = (application as MusicDriveApplication).downloadManager

    override fun getScheduler(): Scheduler = PlatformScheduler(this, JOB_ID)

    override fun getForegroundNotification(downloads: List<Download>, notMetRequirements: Int): Notification =
        notificationHelper.buildProgressNotification(
            this,
            R.drawable.ic_notification_download,
            null,
            null,
            downloads,
            notMetRequirements,
        )
}
