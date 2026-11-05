package com.atube.app.helpers

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.core.content.ContextCompat
import com.atube.app.R

/**
 * The microphone in the search bar.
 *
 * Tapping it dims the screen, shows a pulsing microphone and writes the words as they
 * are recognised. When the speaker stops for two seconds the text is handed back and the
 * search runs on its own, so there is nothing to press.
 */
class VoiceSearchController(
    private val activity: Activity,
    private val onResult: (String) -> Unit
) {

    private var overlay: View? = null
    private var recognizer: SpeechRecognizer? = null
    private var pulse: ObjectAnimator? = null
    private val handler = Handler(Looper.getMainLooper())
    private var heard = StringBuilder()
    private var finished = false

    /** Two seconds of quiet ends the listening, which is what was asked for. */
    private val silence = Runnable { finishWithHeard() }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun permissionRequest() = arrayOf(Manifest.permission.RECORD_AUDIO)

    fun start() {
        if (!hasPermission()) return
        showOverlay()
        listen()
    }

    fun cancel() {
        handler.removeCallbacksAndMessages(null)
        runCatching { recognizer?.cancel() }
        dismiss()
    }

    // ---- listening -----------------------------------------------------------

    private fun listen() {
        heard = StringBuilder()
        finished = false
        setText("")

        if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
            setText(activity.getString(R.string.voice_no_speech))
            handler.postDelayed({ dismiss() }, 1200)
            return
        }

        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(activity).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = armSilence()
                override fun onBeginningOfSpeech() = armSilence()

                override fun onRmsChanged(rmsdB: Float) {
                    // any sound at all pushes the silence window out
                    if (rmsdB > 1f) armSilence()
                    // and the rings breathe with it, so speaking is visible, not just heard
                    reactToVoice(rmsdB)
                }

                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() = Unit

                override fun onError(error: Int) {
                    if (finished) return
                    if (heard.isNotEmpty()) {
                        finishWithHeard()
                    } else {
                        setText(activity.getString(R.string.voice_no_speech))
                        handler.postDelayed({ dismiss() }, 1200)
                    }
                }

                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    if (text.isNotBlank()) heard = StringBuilder(text)
                    finishWithHeard()
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val text = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    if (text.isNotBlank()) {
                        heard = StringBuilder(text)
                        setText(text)
                        armSilence()
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, activity.packageName)
                // up to ten seconds of speech, then the engine stops on its own
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            }
            startListening(intent)
        }
    }

    private fun armSilence() {
        handler.removeCallbacks(silence)
        handler.postDelayed(silence, 2000)
    }

    private fun finishWithHeard() {
        if (finished) return
        finished = true
        handler.removeCallbacksAndMessages(null)
        val text = heard.toString().trim()
        runCatching { recognizer?.stopListening() }
        dismiss()
        if (text.isNotEmpty()) onResult(text)
    }

    // ---- the sheet -----------------------------------------------------------

    private fun showOverlay() {
        if (overlay != null) return
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val view = LayoutInflater.from(activity)
            .inflate(R.layout.dialog_voice_search, root, false)
        root.addView(view)
        overlay = view

        view.findViewById<View>(R.id.voiceCancel).setOnClickListener { cancel() }

        val card = view.findViewById<View>(R.id.voiceCard)
        val micCircle = view.findViewById<View>(R.id.voiceMic)
        val ringOuter = view.findViewById<View>(R.id.voiceRingOuter)
        val pulseView = view.findViewById<View>(R.id.voicePulse)

        // the scrim fades, the card lifts into place, the rings open out - three short
        // movements rather than one flat fade, which is what makes it feel considered
        view.alpha = 0f
        view.animate().alpha(1f).setDuration(160).start()

        card.scaleX = 0.92f
        card.scaleY = 0.92f
        card.alpha = 0f
        card.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(300)
            .setInterpolator(OvershootInterpolator(1.1f))
            .start()

        micCircle.scaleX = 0.6f
        micCircle.scaleY = 0.6f
        micCircle.animate()
            .scaleX(1f).scaleY(1f)
            .setDuration(340)
            .setStartDelay(60)
            .setInterpolator(OvershootInterpolator(1.6f))
            .start()

        ringOuter.scaleX = 0.7f
        ringOuter.scaleY = 0.7f
        ringOuter.alpha = 0f
        ringOuter.animate()
            .scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(420)
            .setStartDelay(40)
            .setInterpolator(DecelerateInterpolator())
            .start()

        // a slow idle breath so the sheet is never completely still while waiting
        pulse = ObjectAnimator.ofPropertyValuesHolder(
            pulseView,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.18f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.18f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 0.9f, 0.35f)
        ).apply {
            duration = 1400
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    /** Scales the rings with the incoming volume, eased so it never snaps. */
    private fun reactToVoice(rmsdB: Float) {
        val ring = overlay?.findViewById<View>(R.id.voicePulse) ?: return
        val level = ((rmsdB.coerceIn(0f, 12f) / 12f) * 0.35f) + 1f
        ring.animate().scaleX(level).scaleY(level).setDuration(120).start()
    }

    private fun setText(text: String) {
        val label = overlay?.findViewById<android.widget.TextView>(R.id.voiceText) ?: return
        if (label.text == text) return
        // each new phrase fades and lifts in, so the words appear as they are spoken
        label.animate().cancel()
        label.alpha = 0f
        label.translationY = 8f * activity.resources.displayMetrics.density
        label.text = text
        label.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(180)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun dismiss() {
        pulse?.cancel()
        pulse = null
        val view = overlay ?: return
        overlay = null
        val card = view.findViewById<View>(R.id.voiceCard)
        card?.animate()?.scaleX(0.95f)?.scaleY(0.95f)?.alpha(0f)?.setDuration(140)?.start()
        view.animate().alpha(0f).setDuration(170).withEndAction {
            runCatching { (view.parent as? ViewGroup)?.removeView(view) }
        }.start()
        runCatching { recognizer?.destroy() }
        recognizer = null
    }
}
