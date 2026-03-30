package com.guardiansoftech.callgateai.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Base64
import android.util.Log
import com.guardiansoftech.callgateai.call.CallManager
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue

class AudioBridge(private val context: Context) {
    companion object {
        private const val TAG = "AudioBridge"
        const val SAMPLE_RATE = 24000
        const val CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO
        const val CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var captureJob: Job? = null
    private var playbackJob: Job? = null
    private var isRunning = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val playbackQueue = ConcurrentLinkedQueue<ByteArray>()

    var onAudioCaptured: ((String) -> Unit)? = null

    private fun debug(msg: String) {
        Log.d(TAG, msg)
        CallManager.debugLog.add("[${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())}] AUDIO: $msg")
        if (CallManager.debugLog.size > 100) CallManager.debugLog.removeAt(0)
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        debug("Starting acoustic bridge")

        // Speakerphone is now handled by InCallService.setAudioRoute()
        // Just max out volumes
        maxVolume()

        startCapture()
        startPlayback()
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        debug("Stopping acoustic bridge")

        captureJob?.cancel()
        playbackJob?.cancel()

        try { audioRecord?.stop(); audioRecord?.release() }
        catch (e: Exception) { debug("Error stopping AudioRecord: ${e.message}") }

        try { audioTrack?.stop(); audioTrack?.release() }
        catch (e: Exception) { debug("Error stopping AudioTrack: ${e.message}") }

        audioRecord = null
        audioTrack = null
        playbackQueue.clear()
    }

    fun enqueueAudio(base64Audio: String) {
        try {
            val audioData = Base64.decode(base64Audio, Base64.NO_WRAP)
            playbackQueue.add(audioData)
        } catch (e: Exception) {
            debug("Error decoding playback audio: ${e.message}")
        }
    }

    fun clearPlaybackQueue() {
        playbackQueue.clear()
    }

    private fun maxVolume() {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxCall = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxCall, 0)
            val maxMusic = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusic, 0)
            debug("Volume maxed: call=$maxCall music=$maxMusic")
        } catch (e: Exception) {
            debug("Volume FAILED: ${e.message}")
        }
    }

    private fun startCapture() {
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT)
        if (bufferSize == AudioRecord.ERROR_BAD_VALUE || bufferSize == AudioRecord.ERROR) {
            debug("Invalid AudioRecord buffer size: $bufferSize")
            return
        }

        val sources = listOf(
            Pair(MediaRecorder.AudioSource.MIC, "MIC"),
            Pair(MediaRecorder.AudioSource.DEFAULT, "DEFAULT"),
            Pair(MediaRecorder.AudioSource.VOICE_COMMUNICATION, "VOICE_COMM")
        )

        for ((source, name) in sources) {
            try {
                val record = AudioRecord(source, SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT, bufferSize * 4)
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    audioRecord = record
                    debug("AudioRecord OK: source=$name bufSize=$bufferSize")
                    break
                } else {
                    record.release()
                    debug("AudioRecord $name: failed to init")
                }
            } catch (e: Exception) {
                debug("AudioRecord $name: ${e.message}")
            }
        }

        if (audioRecord == null) {
            debug("ALL AudioRecord sources FAILED")
            return
        }

        // Disable echo cancellation so mic picks up speaker audio (AI voice)
        // and sends it through the cellular call to the remote caller
        val sessionId = audioRecord!!.audioSessionId
        if (AcousticEchoCanceler.isAvailable()) {
            try {
                val aec = AcousticEchoCanceler.create(sessionId)
                aec?.enabled = false
                debug("AcousticEchoCanceler DISABLED (sessionId=$sessionId)")
            } catch (e: Exception) {
                debug("AEC disable failed: ${e.message}")
            }
        } else {
            debug("AcousticEchoCanceler not available")
        }

        // Also disable noise suppressor so AI audio isn't filtered
        if (NoiseSuppressor.isAvailable()) {
            try {
                val ns = NoiseSuppressor.create(sessionId)
                ns?.enabled = false
                debug("NoiseSuppressor DISABLED")
            } catch (e: Exception) {
                debug("NS disable failed: ${e.message}")
            }
        }

        audioRecord?.startRecording()
        debug("AudioRecord recording started")

        var captureCount = 0
        captureJob = scope.launch {
            val buffer = ByteArray(bufferSize)
            while (isRunning && isActive) {
                val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (bytesRead > 0) {
                    captureCount++
                    val base64 = Base64.encodeToString(buffer.copyOf(bytesRead), Base64.NO_WRAP)
                    onAudioCaptured?.invoke(base64)
                    // Log every 100 captures (~few seconds)
                    if (captureCount % 100 == 0) {
                        debug("Capture running: $captureCount chunks sent to AI")
                    }
                }
            }
            debug("Capture loop ended after $captureCount chunks")
        }
    }

    private fun startPlayback() {
        val bufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_OUT, AUDIO_FORMAT)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL_CONFIG_OUT)
                    .setEncoding(AUDIO_FORMAT)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack?.play()
        debug("AudioTrack started: bufSize=$bufferSize")

        var playbackCount = 0
        playbackJob = scope.launch {
            while (isRunning && isActive) {
                val audioData = playbackQueue.poll()
                if (audioData != null) {
                    playbackCount++
                    audioTrack?.write(audioData, 0, audioData.size)
                    if (playbackCount % 50 == 0) {
                        debug("Playback running: $playbackCount chunks played")
                    }
                } else {
                    delay(10)
                }
            }
            debug("Playback loop ended after $playbackCount chunks")
        }
    }
}
