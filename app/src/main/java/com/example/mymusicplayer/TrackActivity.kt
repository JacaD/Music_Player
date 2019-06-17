package com.example.mymusicplayer

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.provider.MediaStore
import android.support.v4.app.NotificationCompat
import android.view.View
import android.widget.SeekBar
import kotlinx.android.synthetic.main.activity_track.*

class TrackActivity : AppCompatActivity() {
    var mp: MediaPlayer? = null
    private var totalTime: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_track)
        mp = MediaPlayerHandler.mediaPlayer

        mp?.isLooping = false
        mp?.setVolume(0.5f, 0.5f)
        totalTime = mp!!.duration
        playBtn.setBackgroundResource(R.drawable.stop)
        trackTitle.text = MediaPlayerHandler.activeAudio!!.title
        // Volume Bar
        volumeBar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekbar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        var volumeNum = progress / 100.0f
                        mp?.setVolume(volumeNum, volumeNum)
                    }
                }
                override fun onStartTrackingTouch(p0: SeekBar?) {
                }
                override fun onStopTrackingTouch(p0: SeekBar?) {
                }
            }
        )

        // Position Bar
        positionBar.max = totalTime
        positionBar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        mp!!.seekTo(progress)
                    }
                }
                override fun onStartTrackingTouch(p0: SeekBar?) {
                }
                override fun onStopTrackingTouch(p0: SeekBar?) {
                }
            }
        )

        // Thread
        Thread(Runnable {
            while (mp != null) {
                try {
                    var msg = Message()
                    msg.what = mp!!.currentPosition
                    handler.sendMessage(msg)
                    Thread.sleep(1000)
                } catch (e: InterruptedException) {
                }
            }
        }).start()
    }

    @SuppressLint("HandlerLeak")
    var handler = object : Handler() {
        override fun handleMessage(msg: Message) {
            if (mp!!.isPlaying) {
                playBtn.setBackgroundResource(R.drawable.stop)
            } else {
                playBtn.setBackgroundResource(R.drawable.play)
            }
            var currentPosition = msg.what

            // Update positionBar
            positionBar.progress = currentPosition

            // Update Labels
            var elapsedTime = createTimeLabel(currentPosition)
            elapsedTimeLabel.text = elapsedTime

            var remainingTime = createTimeLabel(totalTime - currentPosition)
            remainingTimeLabel.text = "-$remainingTime"
        }
    }

    fun createTimeLabel(time: Int): String {
        var timeLabel = ""
        var min = time / 1000 / 60
        var sec = time / 1000 % 60

        timeLabel = "$min:"
        if (sec < 10) timeLabel += "0"
        timeLabel += sec

        return timeLabel
    }

    fun playBtnClick(v: View) {

        if (mp!!.isPlaying) {
            mp!!.pause()
            playBtn.setBackgroundResource(R.drawable.play)
            buildNotification(MediaPlayerHandler.PAUSED)

        } else {
            mp!!.start()
            playBtn.setBackgroundResource(R.drawable.stop)
            buildNotification(MediaPlayerHandler.PLAYING)
        }
    }

    fun btnPrev (v: View) {
        MediaPlayerHandler.previous?.call()
        MediaPlayerHandler.mediaPlayerHandlerThis!!.updateMetaData()
        trackTitle.text = MediaPlayerHandler.activeAudio!!.title
        buildNotification(MediaPlayerHandler.PLAYING)
    }

    fun btnNext(v: View) {
        MediaPlayerHandler.next?.call()
        MediaPlayerHandler.mediaPlayerHandlerThis!!.updateMetaData()
        trackTitle.text = MediaPlayerHandler.activeAudio!!.title
        buildNotification(MediaPlayerHandler.PLAYING)
    }

    private fun buildNotification(playbackStatus: Int) {
        var notificationAction = android.R.drawable.ic_media_pause
        var playPauseAction: PendingIntent? = null
        if (playbackStatus == MediaPlayerHandler.PLAYING) {
            notificationAction = android.R.drawable.ic_media_pause
            playPauseAction = MediaPlayerHandler.playbackAction(1)
        } else if (playbackStatus == MediaPlayerHandler.PAUSED) {
            notificationAction = android.R.drawable.ic_media_play
            playPauseAction = MediaPlayerHandler.playbackAction(0)
        }

        val notificationBuilder = NotificationCompat.Builder(this)
            .setShowWhen(false)
            .setStyle(
                android.support.v4.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(MediaPlayerHandler.mediaSession!!.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setColor(resources.getColor(R.color.colorPrimary))
            .setSmallIcon(android.R.drawable.stat_sys_headset)
            .setContentText(MediaPlayerHandler.activeAudio!!.artist)
            .setContentTitle(MediaPlayerHandler.activeAudio!!.album)
            .setContentInfo(MediaPlayerHandler.activeAudio!!.title)
            .addAction(android.R.drawable.ic_media_previous, "previous", MediaPlayerHandler.playbackAction(3))
            .addAction(notificationAction, "pause", playPauseAction)
            .addAction(android.R.drawable.ic_media_next, "next", MediaPlayerHandler.playbackAction(2)) as NotificationCompat.Builder

        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, TrackActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT
        )

        notificationBuilder.setContentIntent(contentIntent)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(
            MediaPlayerHandler.NOTIFICATION_ID,
            notificationBuilder.build()
        )
    }

    override fun onPause() {
        super.onPause()
        finish()
    }
}