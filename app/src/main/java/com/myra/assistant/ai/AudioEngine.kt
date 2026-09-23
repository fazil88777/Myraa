package com.myra.assistant.ai

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.AudioAttributes
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Low-latency audio capture (mic -> 16kHz PCM) and playback (24kHz PCM) engine.
 * Capture delivers 640-byte (20ms) chunks via a callback on a background thread.
 * Playback is queued and written to an AudioTrack in streaming mode.
 */
class AudioEngine {

    @Volatile private var capturing = false
    private var recorder: AudioRecord? = null
    private var captureThread: Thread? = null

    private var track: AudioTrack? = null
    private var playThread: Thread? = null
    private val playQueue = ConcurrentLinkedQueue<ByteArray>()
    @Volatile private var playing = false

    /** Start capturing microphone audio as 16kHz mono 16-bit PCM chunks. */
    fun startCapture(onChunk: (ByteArray) -> Unit) {
        try {
            val minBuf = AudioRecord.getMinBufferSize(
                16000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val rec = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                16000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                return
            }
            recorder = rec
            rec.startRecording()
            capturing = true
            captureThread = Thread({
                val buf = ByteArray(640) // 20ms @ 16kHz mono 16-bit
                while (capturing) {
                    try {
                        val n = rec.read(buf, 0, buf.size)
                        if (n > 0) {
                            onChunk(buf.copyOf(n))
                        }
                    } catch (_: Exception) {
                        break
                    }
                }
            }, "myra-capture").also { it.start() }
        } catch (_: Exception) {
        }
    }

    /** Stop capture and release the AudioRecord. */
    fun stopCapture() {
        try {
            capturing = false
            captureThread?.join(500)
            captureThread = null
            recorder?.stop()
            recorder?.release()
            recorder = null
        } catch (_: Exception) {
        }
    }

    /** Enqueue 24kHz mono 16-bit PCM audio for playback; track/thread start lazily. */
    fun playPcm24k(data: ByteArray) {
        try {
            playQueue.offer(data)
            if (track == null) {
                val minBuf = AudioTrack.getMinBufferSize(
                    24000,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val format = AudioFormat.Builder()
                    .setSampleRate(24000)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
                val t = AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(minBuf * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                track = t
                t.play()
                playing = true
                playThread = Thread({
                    while (playing) {
                        try {
                            val chunk = playQueue.poll()
                            if (chunk == null) {
                                Thread.sleep(10)
                                continue
                            }
                            val cur = track ?: break
                            var offset = 0
                            while (offset < chunk.size) {
                                val w = cur.write(chunk, offset, chunk.size - offset, AudioTrack.WRITE_BLOCKING)
                                if (w <= 0) break
                                offset += w
                            }
                        } catch (_: Exception) {
                            break
                        }
                    }
                }, "myra-playback").also { it.start() }
            }
        } catch (_: Exception) {
        }
    }

    /** Drop pending playback and pause/flush the track. */
    fun stopPlayback() {
        try {
            playQueue.clear()
            track?.pause()
            track?.flush()
        } catch (_: Exception) {
        }
    }

    /** Release everything: capture, playback thread, and the AudioTrack. */
    fun release() {
        try {
            stopCapture()
            stopPlayback()
            playing = false
            playThread?.join(500)
            playThread = null
            track?.release()
            track = null
        } catch (_: Exception) {
        }
    }
}
