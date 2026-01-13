package com.example.jarvis

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
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
            // Pehle assets copy karein
            val espeakDataPath = copyAssetFolder("espeak-ng-data")
            initSTT()
            initTTS(espeakDataPath)
        }
    }

    private fun initSTT() {
        try {
            Log.d("VoiceManager", "Initializing STT...")
            val encoder = copyAssetFile("tiny-encoder.int8.onnx")
            val decoder = copyAssetFile("tiny-decoder.int8.onnx")
            val tokens = copyAssetFile("tokens.txt")

            if (encoder == null || decoder == null || tokens == null) {
                Log.e("VoiceManager", "CRITICAL: Missing STT assets")
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

    // Updated initTTS to accept espeak path
    private fun initTTS(espeakDataPath: String?) {
        try {
            Log.d("VoiceManager", "Initializing TTS...")
            val model = copyAssetFile("model-pratham.onnx")
            val tokens = copyAssetFile("tokens-pratham.txt")

            if (model == null || tokens == null) {
                Log.e("VoiceManager", "CRITICAL: Missing TTS assets")
                return
            }
            
            val ttsConfig = OfflineTtsConfig().apply {
                this.model = OfflineTtsModelConfig().apply {
                    vits = OfflineTtsVitsModelConfig().apply {
                        this.model = model
                        this.tokens = tokens
                        // Agar espeak data mila hai, to uska path set karein
                        if (espeakDataPath != null) {
                            this.dataDir = espeakDataPath
                        }
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
            e.printStackTrace()
        }
    }

    // ... existing listening/speaking code ...

    @SuppressLint("MissingPermission")
    fun startListening() {
        if (isRecording) return
        if (recognizer == null) {
            Log.w("VoiceManager", "Recognizer not ready")
            return
        }
        
        if (stream == null) stream = recognizer?.createStream()

        val minBuff = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = maxOf(minBuff, 4096)
        
        try {
            audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, bufferSize)
            audioRecord?.startRecording()
            isRecording = true
            _isListening.value = true

            recordingJob = scope.launch(Dispatchers.IO) {
                val buffer = ShortArray(1024)
                while (isRecording && isActive) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        val floatArray = FloatArray(read) { buffer[it] / 32768f }
                        
                        var maxAmp = 0f
                        for (s in floatArray) {
                            if (Math.abs(s) > maxAmp) maxAmp = Math.abs(s)
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
                             recognizer?.reset(stream)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("VoiceManager", "Error recording", e)
            stopListening()
        }
    }

    fun stopListening() {
        isRecording = false
        _isListening.value = false
        recordingJob?.cancel()
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {}
        audioRecord = null
    }

    fun speak(text: String) {
        if (tts == null) return
        val wasListening = isRecording
        if (wasListening) stopListening()

        scope.launch {
            try {
                val audio = tts?.generate(text, sid = 0, speed = 1.0f)
                if (audio != null) playAudio(audio.samples, audio.sampleRate)
                if (wasListening) {
                    delay(500)
                    startListening()
                }
            } catch (e: Exception) {
                Log.e("VoiceManager", "Error speaking", e)
            }
        }
    }

    private fun playAudio(samples: FloatArray, sampleRate: Int) {
        val minBuff = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val bufferSize = maxOf(minBuff, samples.size * 4)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
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

    // Helper to copy a single file
    private fun copyAssetFile(filename: String): String? {
        val file = File(context.filesDir, filename)
        if (file.exists() && file.length() > 0) return file.absolutePath
        return try {
            context.assets.open(filename).use { input ->
                FileOutputStream(file).use { output -> input.copyTo(output) }
            }
            file.absolutePath
        } catch (e: IOException) {
            Log.e("VoiceManager", "Failed to copy $filename", e)
            null
        }
    }

    // Helper to copy a full folder (Recursive)
    private fun copyAssetFolder(path: String): String? {
        try {
            val fileList = context.assets.list(path) ?: return null
            if (fileList.isEmpty()) return null // It's a file or empty

            val targetDir = File(context.filesDir, path)
            if (!targetDir.exists()) targetDir.mkdirs()

            // Agar folder pehle se hai, to assume karte hain copy ho chuka hai (Startup speed ke liye)
            // Agar update karna ho to version check lagana padega
            if (targetDir.list()?.isNotEmpty() == true) {
                Log.d("VoiceManager", "Folder $path already exists, skipping copy.")
                return targetDir.absolutePath
            }

            for (filename in fileList) {
                val fullPath = if (path.isEmpty()) filename else "$path/$filename"
                // Check if it's a subfolder
                if (context.assets.list(fullPath)?.isNotEmpty() == true) {
                    copyAssetFolder(fullPath)
                } else {
                    // It's a file, copy it
                    context.assets.open(fullPath).use { input ->
                        File(targetDir, filename).outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
            Log.d("VoiceManager", "Copied folder: $path")
            return targetDir.absolutePath
        } catch (e: IOException) {
            Log.e("VoiceManager", "Error copying folder $path", e)
            return null
        }
    }
}