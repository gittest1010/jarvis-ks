package com.example.jarvis

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class VoiceManager(private val context: Context) {
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private var tts: OfflineTts? = null

    private val _isListening = MutableStateFlow(false)
    val isListening = _isListening.asStateFlow()

    private val _recognizedText = MutableStateFlow("")
    val recognizedText = _recognizedText.asStateFlow()

    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel = _audioLevel.asStateFlow()

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingJob: Job? = null

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    init {
        scope.launch {
            initSTT()
            initTTS()
        }
    }

    private fun initSTT() {
        try {
            val encoder = copyAsset("tiny-encoder.int8.onnx")
            val decoder = copyAsset("tiny-decoder.int8.onnx")
            val tokens = copyAsset("tokens.txt")

            if (encoder == null || decoder == null || tokens == null) {
                Log.e("VoiceManager", "Missing STT assets for Whisper")
                return
            }

            val modelConfig = OnlineModelConfig().apply {
                modelType = "whisper"
                whisper.encoder = encoder
                whisper.decoder = decoder
                this.tokens = tokens
                debug = true
                numThreads = 1
            }
            
            val config = OnlineRecognizerConfig().apply {
                this.modelConfig = modelConfig
                featConfig = FeatureConfig().apply {
                    sampleRate = 16000
                    featureDim = 80
                }
                enableEndpoint = true
                ruleConfig = OnlineCtcFstDecoderConfig()
                endpointConfig = EndpointConfig().apply {
                    rule1 = EndpointRule(false, 2.4f, 0.0f)
                    rule2 = EndpointRule(true, 1.2f, 0.0f)
                    rule3 = EndpointRule(false, 0.0f, 20.0f)
                }
            }

            recognizer = OnlineRecognizer(config = config)
            stream = recognizer?.createStream()
            Log.i("VoiceManager", "STT Initialized")

        } catch (e: Exception) {
            Log.e("VoiceManager", "Error initializing STT: ${e.message}")
        }
    }

    private fun initTTS() {
        try {
            val model = copyAsset("model-pratham.onnx")
            val tokens = copyAsset("tokens-pratham.txt")

            if (model == null || tokens == null) {
                Log.e("VoiceManager", "Missing TTS assets")
                return
            }
            
            val ttsConfig = OfflineTtsConfig().apply {
                this.model = OfflineTtsModelConfig().apply {
                    vits = OfflineTtsVitsModelConfig().apply {
                        this.model = model
                        this.tokens = tokens
                        noiseScale = 0.667f
                        noiseScaleW = 0.8f
                        lengthScale = 1.0f
                    }
                    numThreads = 1
                    debug = true
                }
            }

            tts = OfflineTts(config = ttsConfig)
            Log.i("VoiceManager", "TTS Initialized")
        } catch (e: Exception) {
            Log.e("VoiceManager", "Error initializing TTS: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun startListening() {
        if (isRecording) return
        if (recognizer == null) {
            Log.e("VoiceManager", "Recognizer not initialized")
            return
        }
        
        // Reset or recreate stream if needed
        if (stream == null) {
             stream = recognizer?.createStream()
        }

        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
             Log.e("VoiceManager", "Invalid buffer size")
             return
        }
        
        audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, bufferSize)

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e("VoiceManager", "AudioRecord init failed")
            return
        }

        audioRecord?.startRecording()
        isRecording = true
        _isListening.value = true

        recordingJob = scope.launch(Dispatchers.IO) {
            val buffer = ShortArray(bufferSize / 2)
            while (isRecording && isActive) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    val floatArray = FloatArray(read) { buffer[it] / 32768f }
                    
                    // Update visualizer level
                    var maxAmp = 0f
                    for (s in floatArray) {
                        val abs = Math.abs(s)
                        if (abs > maxAmp) maxAmp = abs
                    }
                    _audioLevel.value = maxAmp

                    stream?.acceptWaveform(floatArray, sampleRate)
                    
                    while (recognizer?.isReady(stream) == true) {
                        recognizer?.decode(stream)
                    }
                    
                    val text = recognizer?.getResult(stream)?.text
                    if (!text.isNullOrEmpty()) {
                        val cleanText = text.lowercase().trim()
                        if (_recognizedText.value != cleanText) {
                             _recognizedText.value = cleanText
                        }
                    }
                    
                    if (recognizer?.isEndpoint(stream) == true) {
                         Log.i("VoiceManager", "Endpoint detected")
                         recognizer?.reset(stream)
                    }
                }
            }
        }
    }

    fun stopListening() {
        isRecording = false
        _isListening.value = false
        recordingJob?.cancel()
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e("VoiceManager", "Error stopping AudioRecord", e)
        }
        audioRecord = null
    }

    fun speak(text: String) {
        if (tts == null) {
            Log.e("VoiceManager", "TTS not initialized")
            return
        }
        scope.launch {
            try {
                // sid=0 is default speaker ID
                val audio = tts?.generate(text, sid = 0, speed = 1.0f)
                if (audio != null) {
                    val samples = audio.samples
                    val sampleRate = audio.sampleRate
                    playAudio(samples, sampleRate)
                } else {
                    Log.e("VoiceManager", "TTS generation returned null")
                }
            } catch (e: Exception) {
                Log.e("VoiceManager", "Error speaking", e)
            }
        }
    }
    
    private fun playAudio(samples: FloatArray, sampleRate: Int) {
         val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )
        if (bufferSize <= 0) {
             Log.e("VoiceManager", "Invalid AudioTrack buffer size")
             return
        }

        val track = AudioTrack.Builder()
            .setAudioAttributes(android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build())
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            
        track.play()
        track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
        track.stop()
        track.release()
    }

    fun release() {
        stopListening()
        scope.cancel()
        stream?.release()
        recognizer?.release()
        tts?.release()
    }

    private fun copyAsset(filename: String): String? {
        val file = File(context.filesDir, filename)
        if (file.exists()) return file.absolutePath
        return try {
            context.assets.open(filename).use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
            file.absolutePath
        } catch (e: IOException) {
            Log.e("VoiceManager", "Failed to copy asset $filename", e)
            null
        }
    }
}
