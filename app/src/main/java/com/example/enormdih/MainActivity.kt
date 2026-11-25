package com.example.enormdih

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var ready = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#101820"))
            setPadding(48, 48, 48, 48)
        }

        val title = TextView(this).apply {
            text = "ENORMDIH"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 36f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }

        val button = Button(this).apply {
            text = "PLAY"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#E63946"))
            setOnClickListener { speak() }
        }
        val lp = LinearLayout.LayoutParams(720, 360).apply { topMargin = 96 }

        root.addView(title)
        root.addView(button, lp)
        setContentView(root)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            tts?.setSpeechRate(0.8f)
            tts?.setPitch(0.6f) // deep voice
            ready = true
        } else {
            Toast.makeText(this, "Text-to-speech not available", Toast.LENGTH_LONG).show()
        }
    }

    private fun speak() {
        if (!ready) {
            Toast.makeText(this, "Voice still loading, try again", Toast.LENGTH_SHORT).show()
            return
        }
        tts?.speak("enormdih", TextToSpeech.QUEUE_FLUSH, null, "enormdih")
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
