package com.example.mymusicplayer

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.session.MediaSessionManager
import android.os.*
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.app.NotificationCompat
import android.support.v4.app.NotificationCompat.Builder
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.view.View
import android.widget.SeekBar
import kotlinx.android.synthetic.main.activity_track.*

import java.io.IOException
import java.util.ArrayList
import kotlin.reflect.KFunction

class MediaPlayerHandler : Service(), MediaPlayer.OnCompletionListener, MediaPlayer.OnPreparedListener,
    MediaPlayer.OnErrorListener, MediaPlayer.OnSeekCompleteListener, MediaPlayer.OnInfoListener,
    MediaPlayer.OnBufferingUpdateListener, AudioManager.OnAudioFocusChangeListener {


    private val iBinder = LocalBinder()
//    private var totalTime: Int = 0

    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            pauseMedia()
            buildNotification(PAUSED)
        }
    }

    private val playNewAudio = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {

            audioIndex = TrackManager(applicationContext).loadAudioIndex()
            if (audioIndex != -1 && audioIndex < audioList!!.size) {
                activeAudio = audioList!![audioIndex]
            } else {
                stopSelf()
            }
            stopMedia()
            mediaPlayer!!.reset()
            initMediaPlayer()
            updateMetaData()
            buildNotification(PLAYING)
            val trackIntent = Intent(context, TrackActivity::class.java)
            trackIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(trackIntent)
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        return iBinder
    }

    override fun onCreate() {
        super.onCreate()
        registerBecomingNoisyReceiver()
        registerPlayNewAudio()
        mediaPlayerHandlerThis = this
        next = this::skipToNext
        previous = this::skipToPrevious
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        try {
            val storage = TrackManager(applicationContext)
            audioList = storage.loadAudio()
            audioIndex = storage.loadAudioIndex()

            if (audioIndex != -1 && audioIndex < audioList!!.size) {
                activeAudio = audioList!![audioIndex]
            } else {
                stopSelf()
            }
        } catch (e: NullPointerException) {
            stopSelf()
        }

        if (!requestAudioFocus()) {
            stopSelf()
        }

        if (mediaSessionManager == null) {
            try {
                initMediaSession()
                initMediaPlayer()
            } catch (e: RemoteException) {
                e.printStackTrace()
                stopSelf()
            }

            buildNotification(PLAYING)
            val trackIntent = Intent(this, TrackActivity::class.java)
            trackIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(trackIntent)

        }

        handleIncomingActions(intent)
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onUnbind(intent: Intent): Boolean {
        mediaSession!!.release()
        removeNotification()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (mediaPlayer != null) {
            stopMedia()
            mediaPlayer!!.release()
        }
        removeAudioFocus()

        removeNotification()
        unregisterReceiver(becomingNoisyReceiver)
        unregisterReceiver(playNewAudio)

        TrackManager(applicationContext).clearCachedAudioPlaylist()
    }

    inner class LocalBinder : Binder() {
        val service: MediaPlayerHandler
            get() = this@MediaPlayerHandler
    }

    override fun onBufferingUpdate(mp: MediaPlayer, percent: Int) {}

    override fun onCompletion(mp: MediaPlayer) {
        stopMedia()
        removeNotification()
        stopSelf()
    }

    override fun onError(mp: MediaPlayer, what: Int, extra: Int): Boolean {
        when (what) {
            MediaPlayer.MEDIA_ERROR_NOT_VALID_FOR_PROGRESSIVE_PLAYBACK -> Log.d(
                "MediaPlayer Error",
                "MEDIA ERROR NOT VALID FOR PROGRESSIVE PLAYBACK $extra"
            )
            MediaPlayer.MEDIA_ERROR_SERVER_DIED -> Log.d("MediaPlayer Error", "MEDIA ERROR SERVER DIED $extra")
            MediaPlayer.MEDIA_ERROR_UNKNOWN -> Log.d("MediaPlayer Error", "MEDIA ERROR UNKNOWN $extra")
        }
        return false
    }

    override fun onInfo(mp: MediaPlayer, what: Int, extra: Int): Boolean {
        return false
    }

    override fun onPrepared(mp: MediaPlayer) {
        playMedia()
    }

    override fun onSeekComplete(mp: MediaPlayer) {}

    override fun onAudioFocusChange(focusState: Int) {

        when (focusState) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (mediaPlayer == null)
                    initMediaPlayer()
                else if (!mediaPlayer!!.isPlaying) mediaPlayer!!.start()
                mediaPlayer!!.setVolume(1.0f, 1.0f)
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                if (mediaPlayer!!.isPlaying) mediaPlayer!!.stop()
                mediaPlayer!!.release()
                mediaPlayer = null
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (mediaPlayer!!.isPlaying) mediaPlayer!!.pause()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> if (mediaPlayer!!.isPlaying) mediaPlayer!!.setVolume(
                0.1f,
                0.1f
            )
        }
    }

    private fun requestAudioFocus(): Boolean {
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val result = audioManager!!.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun removeAudioFocus(): Boolean {
        return AudioManager.AUDIOFOCUS_REQUEST_GRANTED == audioManager!!.abandonAudioFocus(this)
    }

    private fun initMediaPlayer() {
        if (mediaPlayer == null)
            mediaPlayer = MediaPlayer()

        mediaPlayer!!.setOnCompletionListener(this)
        mediaPlayer!!.setOnErrorListener(this)
        mediaPlayer!!.setOnPreparedListener(this)
        mediaPlayer!!.setOnBufferingUpdateListener(this)
        mediaPlayer!!.setOnSeekCompleteListener(this)
        mediaPlayer!!.setOnInfoListener(this)
        mediaPlayer!!.reset()


        mediaPlayer!!.setAudioStreamType(AudioManager.STREAM_MUSIC)
        try {
            mediaPlayer!!.setDataSource(activeAudio!!.data)
        } catch (e: IOException) {
            e.printStackTrace()
            stopSelf()
        }

        mediaPlayer!!.prepareAsync()
    }

    private fun playMedia() {
        if (!mediaPlayer!!.isPlaying) {
            mediaPlayer!!.start()
        }
    }

    private fun stopMedia() {
        if (mediaPlayer == null) return
        if (mediaPlayer!!.isPlaying) {
            mediaPlayer!!.stop()
        }
    }

    private fun pauseMedia() {
        if (mediaPlayer!!.isPlaying) {
            mediaPlayer!!.pause()
            resumePosition = mediaPlayer!!.currentPosition
        }
    }

    private fun resumeMedia() {
        if (!mediaPlayer!!.isPlaying) {
            mediaPlayer!!.seekTo(resumePosition)
            mediaPlayer!!.start()
        }
    }

    fun skipToNext() {

        if (audioIndex == audioList!!.size - 1) {
            audioIndex = 0
            activeAudio = audioList!![audioIndex]
        } else {
            activeAudio = audioList!![++audioIndex]
        }
        TrackManager(applicationContext).storeAudioIndex(audioIndex)

        stopMedia()
        mediaPlayer!!.reset()
        initMediaPlayer()
    }

    fun skipToPrevious() {

        if (audioIndex == 0) {
            audioIndex = audioList!!.size - 1
            activeAudio = audioList!![audioIndex]
        } else {
            activeAudio = audioList!![--audioIndex]
        }

        TrackManager(applicationContext).storeAudioIndex(audioIndex)
        stopMedia()
        mediaPlayer!!.reset()
        initMediaPlayer()
    }

    private fun registerBecomingNoisyReceiver() {
        val intentFilter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        registerReceiver(becomingNoisyReceiver, intentFilter)
    }

    @Throws(RemoteException::class)
    private fun initMediaSession() {
        if (mediaSessionManager != null) return

        mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        mediaSession = MediaSessionCompat(applicationContext, "AudioPlayer")
        transportControls = mediaSession!!.controller.transportControls
        mediaSession!!.isActive = true
        mediaSession!!.setFlags(MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)

        updateMetaData()

        mediaSession!!.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() {
                super.onPlay()

                resumeMedia()
                buildNotification(PLAYING)
            }

            override fun onPause() {
                super.onPause()

                pauseMedia()
                buildNotification(PAUSED)
            }

            override fun onSkipToNext() {
                super.onSkipToNext()

                skipToNext()
                updateMetaData()
                buildNotification(PLAYING)
            }

            override fun onSkipToPrevious() {
                super.onSkipToPrevious()

                skipToPrevious()
                updateMetaData()
                buildNotification(PLAYING)
            }

            override fun onStop() {
                super.onStop()
                removeNotification()
                stopSelf()
            }

            override fun onSeekTo(position: Long) {
                super.onSeekTo(position)
            }
        })
    }

    public fun updateMetaData() {
        mediaSession!!.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, activeAudio!!.artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, activeAudio!!.album)
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, activeAudio!!.title)
                .build()
        )
    }

    private fun buildNotification(playbackStatus: Int) {
        var notificationAction = android.R.drawable.ic_media_pause
        var playPauseAction: PendingIntent? = null
        if (playbackStatus == PLAYING) {
            notificationAction = android.R.drawable.ic_media_pause
            playPauseAction = playbackAction(1)
        } else if (playbackStatus == PAUSED) {
            notificationAction = android.R.drawable.ic_media_play
            playPauseAction = playbackAction(0)
        }

        val notificationBuilder = Builder(this)
            .setShowWhen(false)
            .setStyle(
                NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession!!.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setColor(resources.getColor(R.color.colorPrimary))
            .setSmallIcon(android.R.drawable.stat_sys_headset)
            .setContentText(activeAudio!!.artist)
            .setContentTitle(activeAudio!!.album)
            .setContentInfo(activeAudio!!.title)
            .addAction(android.R.drawable.ic_media_previous, "previous", playbackAction(3))
            .addAction(notificationAction, "pause", playPauseAction)
            .addAction(android.R.drawable.ic_media_next, "next", playbackAction(2)) as Builder

        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, TrackActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT
        )

        notificationBuilder.setContentIntent(contentIntent)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(
            NOTIFICATION_ID,
            notificationBuilder.build()
        )
    }


    private fun playbackAction(actionNumber: Int): PendingIntent? {
        val playbackAction = Intent(this, MediaPlayerHandler::class.java)
        when (actionNumber) {
            0 -> {
                playbackAction.action = ACTION_PLAY
                return PendingIntent.getService(this, actionNumber, playbackAction, 0)
            }
            1 -> {
                playbackAction.action = ACTION_PAUSE
                return PendingIntent.getService(this, actionNumber, playbackAction, 0)
            }
            2 -> {
                playbackAction.action = ACTION_NEXT
                return PendingIntent.getService(this, actionNumber, playbackAction, 0)
            }
            3 -> {
                playbackAction.action = ACTION_PREVIOUS
                return PendingIntent.getService(this, actionNumber, playbackAction, 0)
            }
            else -> {
            }
        }
        return null
    }

    private fun removeNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)
    }

    private fun handleIncomingActions(playbackAction: Intent?) {
        if (playbackAction == null || playbackAction.action == null) return

        val actionString = playbackAction.action
        when {
            actionString!!.equals(ACTION_PLAY, ignoreCase = true) -> transportControls!!.play()
            actionString.equals(ACTION_PAUSE, ignoreCase = true) -> transportControls!!.pause()
            actionString.equals(ACTION_NEXT, ignoreCase = true) -> transportControls!!.skipToNext()
            actionString.equals(ACTION_PREVIOUS, ignoreCase = true) -> transportControls!!.skipToPrevious()
            actionString.equals(ACTION_STOP, ignoreCase = true) -> transportControls!!.stop()
        }
    }

    private fun registerPlayNewAudio() {
        val filter = IntentFilter(MainActivity.Broadcast_PLAY_NEW_AUDIO)
        registerReceiver(playNewAudio, filter)
    }

    companion object {
        var mediaPlayerHandlerThis: MediaPlayerHandler? = null
        fun playbackAction(actionNumber: Int): PendingIntent? {
            val playbackAction = Intent(mediaPlayerHandlerThis, MediaPlayerHandler::class.java)
            when (actionNumber) {
                0 -> {
                    playbackAction.action = ACTION_PLAY
                    return PendingIntent.getService(mediaPlayerHandlerThis, actionNumber, playbackAction, 0)
                }
                1 -> {
                    playbackAction.action = ACTION_PAUSE
                    return PendingIntent.getService(mediaPlayerHandlerThis, actionNumber, playbackAction, 0)
                }
                2 -> {
                    playbackAction.action = ACTION_NEXT
                    return PendingIntent.getService(mediaPlayerHandlerThis, actionNumber, playbackAction, 0)
                }
                3 -> {
                    playbackAction.action = ACTION_PREVIOUS
                    return PendingIntent.getService(mediaPlayerHandlerThis, actionNumber, playbackAction, 0)
                }
                else -> {
                }
            }
            return null
        }

        var mediaPlayer: MediaPlayer? = null
        var mediaSessionManager: MediaSessionManager? = null
        var mediaSession: MediaSessionCompat? = null
        var transportControls: MediaControllerCompat.TransportControls? = null

        var resumePosition: Int = 0

        var audioManager: AudioManager? = null

        var next: KFunction<Unit>? = null
        var previous: KFunction<Unit>? = null


        var audioList: ArrayList<Track>? = null
        var audioIndex = -1
        var activeAudio: Track? = null
        const val ACTION_PLAY = "com.example.mymusicplayer.ACTION_PLAY"
        const val ACTION_PAUSE = "com.example.mymusicplayer.ACTION_PAUSE"
        const val ACTION_PREVIOUS = "com.example.mymusicplayer.ACTION_PREVIOUS"
        const val ACTION_NEXT = "com.example.mymusicplayer.ACTION_NEXT"
        const val ACTION_STOP = "com.example.mymusicplayer.ACTION_STOP"
        const val PAUSED = 0
        const val PLAYING = 1

        const val NOTIFICATION_ID = 101
    }

}
