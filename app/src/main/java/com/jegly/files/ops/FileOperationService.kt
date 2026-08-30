package com.jegly.files.ops

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.jegly.files.R
import com.jegly.files.security.ArchiveCrypto.wipe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Runs a batch operation as a foreground service so a large copy survives the user
 * leaving the app. Progress is mirrored into [state] for the UI to observe; the
 * notification is the OS-facing view of the same thing.
 */
class FileOperationService : Service() {

    private val scope = CoroutineScope(SupervisorJob())
    private var worker: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent ?: return START_NOT_STICKY

        if (intent.action == ACTION_CANCEL) {
            worker?.cancel()
            /*
             * Report a terminal state, the way AOSP's Job does: cancel() sets STATE_CANCELED,
             * isFinished() treats CANCELED and COMPLETED alike, and run()'s finally block calls
             * listener.onFinished(this) whether or not the job was cancelled.
             *
             * Without the equivalent here, _state stays on its last Running value forever — the
             * cancelling coroutine never reaches the stopForeground/stopSelf after collect, and
             * nothing else writes it. The UI's banner then shows a permanent progress bar whose
             * only button is Cancel, and acknowledgeOperation() — the one path that clears the
             * banner and re-lists the directory — becomes unreachable.
             */
            _state.value = (_state.value as? OpProgress.Running)?.let {
                OpProgress.Finished(
                    kind = it.kind,
                    succeeded = it.filesDone,
                    skipped = 0,
                    failures = emptyList(),
                    cancelled = true,
                )
            }
            stopSelf()
            return START_NOT_STICKY
        }

        val kind = OpKind.valueOf(intent.getStringExtra(EXTRA_KIND) ?: return START_NOT_STICKY)
        val sources = intent.getStringArrayListExtra(EXTRA_SOURCES).orEmpty().map(::File)
        val destination = intent.getStringExtra(EXTRA_DEST)?.let(::File)
        val policy = intent.getStringExtra(EXTRA_POLICY)
            ?.let(ConflictPolicy::valueOf) ?: ConflictPolicy.KeepBoth
        // Claimed from the in-process handoff, never read out of the Intent — see [start].
        val password = claimPassword(intent.getLongExtra(EXTRA_PASSWORD_TOKEN, 0L))

        startForeground(
            NOTIFICATION_ID,
            buildNotification(kind, 0f, sources.firstOrNull()?.name.orEmpty()),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

        worker?.cancel()
        worker = scope.launch {
            try {
                FileOperations().run(kind, sources, destination, policy, password)
                    .collect { progress ->
                        _state.value = progress
                        if (progress is OpProgress.Running) {
                            notificationManager.notify(
                                NOTIFICATION_ID,
                                buildNotification(kind, progress.fraction, progress.currentName),
                            )
                        }
                    }
            } finally {
                // The key material dies with the operation whether it finished, failed or was
                // cancelled. Nothing downstream retains it.
                password?.wipe()
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private val notificationManager: NotificationManager
        get() = getSystemService(NotificationManager::class.java)

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.op_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(kind: OpKind, fraction: Float, name: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(
                when (kind) {
                    OpKind.Copy -> "Copying"
                    OpKind.Move -> "Moving"
                    OpKind.Delete -> "Deleting"
                    OpKind.Compress -> "Compressing"
                    OpKind.Extract -> "Extracting"
                }
            )
            .setContentText(name)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOnlyAlertOnce(true)
            .setProgress(100, (fraction * 100).toInt(), kind == OpKind.Delete)
            .build()

    companion object {
        private const val CHANNEL_ID = "file_ops"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_CANCEL = "com.jegly.files.CANCEL"
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_SOURCES = "sources"
        private const val EXTRA_DEST = "dest"
        private const val EXTRA_POLICY = "policy"
        private const val EXTRA_PASSWORD_TOKEN = "password_token"

        /**
         * Archive passwords, handed to the service in-process rather than through the Intent.
         *
         * An Intent is the wrong vehicle for a secret: it is marshalled through the system
         * server, and its extras surface in `dumpsys activity`, in bug reports, and in crash
         * dumps. This service is a component of the same process, so the Intent only has to
         * carry a one-shot token naming the entry below; the characters themselves never leave
         * this address space.
         *
         * The map is keyed rather than a single slot so that starting a second operation before
         * the first has been picked up cannot hand it the wrong key. Entries are removed on
         * read, so a token is worthless once used.
         */
        private val pendingPasswords = java.util.concurrent.ConcurrentHashMap<Long, CharArray>()
        private val passwordTokens = java.util.concurrent.atomic.AtomicLong(1L)

        private fun claimPassword(token: Long): CharArray? =
            if (token == 0L) null else pendingPasswords.remove(token)

        private val _state = MutableStateFlow<OpProgress?>(null)
        val state: StateFlow<OpProgress?> = _state.asStateFlow()

        fun clearState() { _state.value = null }

        /**
         * [password] is taken over by the service and zeroed when the operation ends; callers
         * must not reuse or clear the array themselves.
         */
        fun start(
            context: Context,
            kind: OpKind,
            sources: List<File>,
            destination: File?,
            policy: ConflictPolicy = ConflictPolicy.KeepBoth,
            password: CharArray? = null,
        ) {
            val token = password?.let {
                passwordTokens.getAndIncrement().also { t -> pendingPasswords[t] = it }
            } ?: 0L
            val intent = Intent(context, FileOperationService::class.java).apply {
                putExtra(EXTRA_KIND, kind.name)
                putStringArrayListExtra(EXTRA_SOURCES, ArrayList(sources.map { it.absolutePath }))
                putExtra(EXTRA_DEST, destination?.absolutePath)
                putExtra(EXTRA_POLICY, policy.name)
                if (token != 0L) putExtra(EXTRA_PASSWORD_TOKEN, token)
            }
            try {
                context.startForegroundService(intent)
            } catch (t: Throwable) {
                // Never strand key material in the map if the service could not be started.
                claimPassword(token)?.wipe()
                throw t
            }
        }

        fun cancel(context: Context) {
            context.startService(
                Intent(context, FileOperationService::class.java).setAction(ACTION_CANCEL)
            )
        }
    }
}
