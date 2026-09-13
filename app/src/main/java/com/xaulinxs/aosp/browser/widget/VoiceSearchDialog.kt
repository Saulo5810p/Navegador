package com.xaulinxs.aosp.browser.widget

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import com.xaulinxs.aosp.browser.R
import java.util.Locale

/**
 * Popup PRÓPRIO do app pra busca por voz - usa android.speech.SpeechRecognizer
 * diretamente (processamento nativo do Android, sem abrir nenhum app externo
 * do Google), então a UI de "Ouvindo..." é a nossa (dialog_voice_search.xml)
 * e não a tela do sistema que normalmente aparece com
 * RecognizerIntent.ACTION_RECOGNIZE_SPEECH via startActivityForResult.
 *
 * Uso:
 *   VoiceSearchDialog(activity) { recognizedText -> ... }.show()
 *
 * Pré-requisito: permissão android.permission.RECORD_AUDIO já concedida -
 * quem chama (MainActivity/BrowserSearchWidgetProvider->MainActivity) é
 * responsável por pedir a permissão antes de abrir este popup.
 */
class VoiceSearchDialog(
    private val activity: Activity,
    private val onResult: (String) -> Unit
) {

    private var recognizer: SpeechRecognizer? = null
    private var dialog: AlertDialog? = null

    fun show() {
        if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
            Toast.makeText(activity, R.string.voice_search_error_unavailable, Toast.LENGTH_SHORT).show()
            return
        }

        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_voice_search, null)
        val statusText = view.findViewById<TextView>(R.id.voiceSearchStatus)

        dialog = AlertDialog.Builder(activity)
            .setView(view)
            .setNegativeButton(R.string.voice_search_cancel) { _, _ -> cleanup() }
            .setOnCancelListener { cleanup() }
            .show()

        startListening(statusText)
    }

    private fun startListening(statusText: TextView) {
        val speechRecognizer = SpeechRecognizer.createSpeechRecognizer(activity)
        recognizer = speechRecognizer

        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                statusText.setText(R.string.voice_search_listening)
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onError(error: Int) {
                val messageRes = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        R.string.voice_search_error_no_match
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                        R.string.voice_search_error_no_permission
                    else -> R.string.voice_search_error_generic
                }
                Toast.makeText(activity, messageRes, Toast.LENGTH_SHORT).show()
                cleanup()
            }

            override fun onResults(results: Bundle?) {
                val best = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                cleanup()
                if (!best.isNullOrBlank()) onResult(best)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                // Mostra ao vivo o que já foi reconhecido, em vez de deixar
                // o texto travado em "Ouvindo..." o tempo todo.
                val best = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!best.isNullOrBlank()) statusText.text = best
            }
        })

        val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, activity.packageName)
        }
        speechRecognizer.startListening(recognizerIntent)
    }

    private fun cleanup() {
        recognizer?.destroy()
        recognizer = null
        dialog?.dismiss()
        dialog = null
    }
}
