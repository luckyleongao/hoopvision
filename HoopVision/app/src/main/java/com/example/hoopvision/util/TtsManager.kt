package com.example.hoopvision.util

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

class TtsManager(context: Context) : TextToSpeech.OnInitListener {
    private val TAG = "TtsManager"
    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var isInitialized = false
    private var pendingText: String? = null

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            Log.d(TAG, "TTS Engine Initialized successfully. Available engines: ${tts?.engines?.map { it.name }}")
            
            // Try Chinese locales in order of preference
            val localesToTry = listOf(
                Locale.CHINA,
                Locale.SIMPLIFIED_CHINESE,
                Locale.CHINESE,
                Locale.getDefault()
            )

            var langSet = false
            for (loc in localesToTry) {
                val res = tts?.setLanguage(loc)
                if (res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.d(TAG, "TTS setLanguage successful for locale: $loc")
                    langSet = true
                    break
                }
            }

            if (!langSet) {
                Log.w(TAG, "Chinese locale not directly supported, using default locale: ${tts?.defaultVoice?.locale}")
            }

            // Set audio stream to media playback
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            tts?.setAudioAttributes(audioAttributes)

            tts?.setPitch(1.05f)
            tts?.setSpeechRate(1.15f)
            isInitialized = true

            // If a speech was requested before onInit finished, speak it now
            pendingText?.let {
                Log.d(TAG, "Speaking pending text: $it")
                speak(it)
                pendingText = null
            }
        } else {
            Log.e(TAG, "TTS Engine Initialization failed with status code: $status")
        }
    }

    /**
     * Speaks the coach feedback, automatically cleaning up technical bracket tags like 【AI 多模态视觉教练】
     */
    fun speak(text: String) {
        val cleanedText = text
            .replace(Regex("【.*?】"), "")
            .replace(Regex("\\[.*?\\]"), "")
            .replace("*", "")
            .trim()

        if (cleanedText.isEmpty()) return

        if (!isInitialized || tts == null) {
            Log.d(TAG, "TTS not ready yet, queuing text: $cleanedText")
            pendingText = cleanedText
            return
        }

        Log.d(TAG, "TTS speaking: $cleanedText")
        val params = Bundle()
        val result = tts?.speak(
            cleanedText,
            TextToSpeech.QUEUE_FLUSH,
            params,
            "HOOP_COACH_${System.currentTimeMillis()}"
        )
        if (result == TextToSpeech.ERROR) {
            Log.e(TAG, "Error triggering tts.speak()")
        }
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }
}
