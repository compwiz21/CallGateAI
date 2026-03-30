package com.guardiansoftech.callgateai.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Acoustic coupling bridge for AI voice during phone calls.
 *
 * How it works:
 * - Speakerphone is ON during the call
 * - AI audio plays through STREAM_MUSIC (device speaker)
 * - The phone's mic picks up AI audio and sends it through the cellular call
 * - Remote caller's voice comes through the speaker
 * - AudioRecord captures mic input (mix of remote voice + ambient)
 * - Captured audio is sent to OpenAI for processing
 */
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

    fun start() {
        if (isRunning) return
        isRunning = true
        Log.d(TAG, "Starting acoustic bridge")

        // Force speakerphone and max volume
        enableSpeakerAndVolume()

        startCapture()
        startPlayback()

        // Re-enable speakerphone after a delay (dialer may override it)
        scope.launch {
            delay(1000)
            enableSpeakerAndVolume()
            delay(2000)
            enableSpeakerAndVolume()
            delay(5000)
            enableSpeakerAndVolume()
        }
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        Log.d(TAG, "Stopping acoustic bridge")

        captureJob?.cancel()
        playbackJob?.cancel()

        try { audioRecord?.stop(); audioRecord?.release() }
        catch (e: Exception) { Log.e(TAG, "Error stopping AudioRecord", e) }

        try { audioTrack?.stop(); audioTrack?.release() }
        catch (e: Exception) { Log.e(TAG, "Error stopping AudioTrack", e) }

        audioRecord = null
        audioTrack = null
        playbackQueue.clear()
    }

    fun enqueueAudio(base64Audio: String) {
        try {
            val audioData = Base64.decode(base64Audio, Base64.NO_WRAP)
            playbackQueue.add(audioData)
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding audio for playback", e)
        }
    }

    fun clearPlaybackQueue() {
        playbackQueue.clear()
    }

    private fun enableSpeakerAndVolume() {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

            // Enable speakerphone
            audioManager.isSpeakerphoneOn = true
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

            // Max out relevant volumes
            val maxCall = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxCall, 0)

            val maxMusic = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusic, 0)

            Log.d(TAG, "Speakerphone: ${audioManager.isSpeakerphoneOn}, " +
                "mode: ${audioManager.mode}, " +
                "callVol: $maxCall, musicVol: $maxMusic")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set speaker/volume", e)
        }
    }

    private fun startCapture() {
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT)
        if (bufferSize == AudioRecord.ERROR_BAD_VALUE || bufferSize == AudioRecord.ERROR) {
            Log.e(TAG, "Invalid buffer size: $bufferSize")
            return
        }

        // Try MIC source — works during calls and captures speakerphone output
        val sources = listOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.DEFAULT,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        )

        for (source in sources) {
            try {
                val record = AudioRecord(
                    source, SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT, bufferSize * 4
                )
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    audioRecord = record
                    Log.d(TAG, "AudioRecord OK with source: $source")
                    break
                } else {
                    record.release()
                }
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecord source $source failed: ${e.message}")
            }
        }

        if (audioRecord == null) {
            Log.e(TAG, "All AudioRecord sources failed!")
            return
        }

        audioRecord?.startRecording()

        captureJob = scope.launch {
            val buffer = ByteArray(bufferSize)
            while (isRunning && isActive) {
                val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (bytesRead > 0) {
                    val base64 = Base64.encodeToString(buffer.copyOf(bytesRead), Base64.NO_WRAP)
                    onAudioCaptured?.invoke(base64)
                }
            }
        }

        Log.d(TAG, "Capture started")
    }

    private fun startPlayback() {
        val bufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_OUT, AUDIO_FORMAT)

        // Play through MUSIC stream — goes to device speaker
        // The phone mic picks this up and sends it through the cellular call
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

        playbackJob = scope.launch {
            while (isRunning && isActive) {
                val audioData = playbackQueue.poll()
                if (audioData != null) {
                    audioTrack?.write(audioData, 0, audioData.size)
                } else {
                    delay(10)
                }
            }
        }

        Log.d(TAG, "Playback started (STREAM_MUSIC → speaker → mic → call)")
    }
}
