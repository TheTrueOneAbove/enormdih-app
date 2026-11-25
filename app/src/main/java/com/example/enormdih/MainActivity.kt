package com.example.enormdih

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.media.MediaPlayer
import android.util.Base64
import android.text.Editable
import android.text.TextWatcher
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.text.Html
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {

    private companion object {
        const val BG = "#000000"        // true black for OLED
        const val SURFACE = "#0B0B0B"
        const val BORDER = "#202020"
        const val ACCENT = "#FF3B30"
        const val MUTED = "#7A7A7A"
    }

    private var tts: TextToSpeech? = null
    private var ready = false
    private lateinit var prefs: SharedPreferences

    // Voice settings (a gruff, fast, deep British "chef" style preset)
    private var pitch = 0.80f
    private var rate = 1.15f

    private lateinit var directionsOutput: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("settings", MODE_PRIVATE)
        pitch = prefs.getFloat("pitch", pitch)
        rate = prefs.getFloat("rate", rate)
        buildUi()
        initTts(preferGoogle = true)
    }

    // ---------- Voice: Google Cloud voice (built in, uses your key) or the phone's engine ----------

    private var player: MediaPlayer? = null
    private lateinit var statusView: TextView
    private lateinit var sourceBtn: Button

    private fun cloudKey() = prefs.getString("apikey", "").orEmpty().trim()
    private fun useCloud() = cloudKey().isNotEmpty() && prefs.getBoolean("cloud", true)

    private fun setStatus(msg: String) {
        if (::statusView.isInitialized) statusView.text = msg
    }

    private fun refreshStatus() {
        setStatus(
            when {
                useCloud() -> "Voice: Google Cloud (British male). Needs internet."
                ready -> "Voice: phone text-to-speech engine."
                else -> "No voice yet. Add a Google API key below (enable Cloud Text-to-Speech API), " +
                    "or tap INSTALL PHONE VOICE ENGINE."
            }
        )
        if (::sourceBtn.isInitialized) {
            sourceBtn.text = if (prefs.getBoolean("cloud", true)) "SOURCE: GOOGLE CLOUD (TAP TO SWITCH)" else "SOURCE: PHONE ENGINE (TAP TO SWITCH)"
        }
    }

    private fun initTts(preferGoogle: Boolean) {
        ready = false
        tts?.shutdown()
        val hasGoogle = runCatching { packageManager.getPackageInfo("com.google.android.tts", 0); true }.getOrDefault(false)
        usedGoogleEngine = preferGoogle && hasGoogle
        tts = if (usedGoogleEngine) TextToSpeech(this, this, "com.google.android.tts") else TextToSpeech(this, this)
    }

    private var usedGoogleEngine = false

    override fun onInit(status: Int) {
        val t = tts
        if (status != TextToSpeech.SUCCESS || t == null) {
            if (usedGoogleEngine) { initTts(preferGoogle = false); return }
            ready = false
            runOnUiThread { refreshStatus() }
            return
        }
        t.language = Locale.UK
        val british = runCatching {
            t.voices?.filter { it.locale.language == "en" && it.locale.country == "GB" && !it.isNetworkConnectionRequired }
        }.getOrNull().orEmpty()
        val pick = british.firstOrNull { it.name.contains("-gbd-") || it.name.contains("-gbb-") }
            ?: british.firstOrNull()
        if (pick != null) t.voice = pick
        ready = true
        runOnUiThread { refreshStatus() }
    }

    private fun speak(text: String, queue: Boolean = false) {
        if (useCloud()) { cloudSpeak(text); return }
        deviceSpeak(text, queue)
    }

    private fun speakAll(parts: List<String>) {
        if (useCloud()) { cloudSpeak(parts.joinToString(". ")); return }
        parts.forEachIndexed { i, p -> deviceSpeak(p, queue = i > 0) }
    }

    private fun deviceSpeak(text: String, queue: Boolean) {
        val t = tts
        if (!ready || t == null) {
            refreshStatus()
            toast("No phone voice engine. Add a Google API key or install one.")
            return
        }
        t.setPitch(pitch)
        t.setSpeechRate(rate)
        val mode = if (queue) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH
        t.speak(text, mode, null, "utt-${System.nanoTime()}")
    }

    private fun cloudSpeak(text: String) {
        val key = cloudKey()
        setStatus("Preparing audio...")
        thread {
            val result = runCatching {
                val body = JSONObject()
                    .put("input", JSONObject().put("text", text.take(4500)))
                    .put("voice", JSONObject().put("languageCode", "en-GB").put("name", "en-GB-Neural2-D"))
                    .put(
                        "audioConfig", JSONObject()
                            .put("audioEncoding", "MP3")
                            .put("speakingRate", rate.toDouble().coerceIn(0.25, 4.0))
                            .put("pitch", ((pitch - 1f) * 20f).toDouble().coerceIn(-20.0, 20.0))
                    )
                val conn = URL("https://texttospeech.googleapis.com/v1/text:synthesize?key=${enc(key)}")
                    .openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                try {
                    conn.outputStream.use { it.write(body.toString().toByteArray()) }
                    val code = conn.responseCode
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    val resp = stream.bufferedReader().use { it.readText() }
                    if (code !in 200..299) {
                        error(runCatching { JSONObject(resp).getJSONObject("error").getString("message") }
                            .getOrDefault("HTTP $code"))
                    }
                    val audio = Base64.decode(JSONObject(resp).getString("audioContent"), Base64.DEFAULT)
                    File(cacheDir, "tts-${System.nanoTime()}.mp3").also { it.writeBytes(audio) }
                } finally {
                    conn.disconnect()
                }
            }
            runOnUiThread {
                result.onSuccess { f ->
                    refreshStatus()
                    play(f)
                }.onFailure { e ->
                    setStatus("Cloud voice error: ${e.message}")
                    if (ready) deviceSpeak(text, queue = false)
                }
            }
        }
    }

    private fun play(f: File) {
        runCatching { player?.release() }
        player = MediaPlayer().apply {
            setDataSource(f.path)
            setOnCompletionListener { f.delete() }
            prepare()
            start()
        }
    }

    private fun stopAll() {
        tts?.stop()
        runCatching { player?.stop() }
    }

    // ---------- Google Maps (map view + directions, uses the user's own API key) ----------

    private lateinit var mapView: WebView
    private var loadedKey: String? = null

    private val mapHtml = """
<!DOCTYPE html><html><head>
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body,#m{margin:0;height:100%;background:#000}</style></head><body>
<div id="m"></div>
<script>
var map, ds, dr, pending = null;
var DARK = [
 {elementType:'geometry',stylers:[{color:'#000000'}]},
 {elementType:'labels.text.fill',stylers:[{color:'#8a8a8a'}]},
 {elementType:'labels.text.stroke',stylers:[{color:'#000000'}]},
 {featureType:'road',elementType:'geometry',stylers:[{color:'#1c1c1c'}]},
 {featureType:'water',elementType:'geometry',stylers:[{color:'#0a0f14'}]},
 {featureType:'poi',stylers:[{visibility:'off'}]}
];
function initMap(){
  map = new google.maps.Map(document.getElementById('m'),
    {center:{lat:39.5,lng:-98.35},zoom:4,disableDefaultUI:true,zoomControl:true,styles:DARK});
  ds = new google.maps.DirectionsService();
  dr = new google.maps.DirectionsRenderer({map:map,polylineOptions:{strokeColor:'#FF3B30',strokeWeight:5}});
  if (pending) { route(pending[0], pending[1]); pending = null; }
}
function route(o, d){
  if (!ds) { pending = [o, d]; return; }
  ds.route({origin:o,destination:d,travelMode:'DRIVING'}, function(r, s){
    if (s === 'OK') {
      dr.setDirections(r);
      var l = r.routes[0].legs[0];
      var steps = l.steps.map(function(x){ return {t:x.instructions, d:x.distance.text}; });
      Android.onRoute(JSON.stringify({end:l.end_address,dist:l.distance.text,dur:l.duration.text,steps:steps}));
    } else { Android.onError('Directions failed: ' + s); }
  });
}
window.gm_authFailure = function(){ Android.onError('Google rejected the API key. Check the key and that Maps JavaScript API and Directions API are enabled.'); };
</script>
<script src="https://maps.googleapis.com/maps/api/js?key=__KEY__&callback=initMap" async defer></script>
</body></html>
""".trimIndent()

    private inner class Bridge {
        @JavascriptInterface
        fun onRoute(json: String) {
            runOnUiThread {
                runCatching {
                    val j = JSONObject(json)
                    val summary = "Right. Route to ${j.optString("end")}. " +
                        "${j.optString("dist")}, about ${j.optString("dur")}."
                    val steps = j.getJSONArray("steps")
                    val lines = (0 until steps.length()).map { i ->
                        val st = steps.getJSONObject(i)
                        val instr = Html.fromHtml(st.optString("t"), Html.FROM_HTML_MODE_LEGACY).toString().trim()
                        val dist = st.optString("d")
                        if (dist.isBlank()) instr else "$instr, for $dist"
                    }
                    directionsOutput.text = (listOf(summary) + lines.mapIndexed { i, l -> "${i + 1}. $l" })
                        .joinToString("\n")
                    speakAll(listOf(summary) + lines)
                }.onFailure { directionsOutput.text = "Error: ${it.message}" }
            }
        }

        @JavascriptInterface
        fun onError(message: String) {
            runOnUiThread {
                directionsOutput.text = "Error: $message"
                speak("That's not right. Check your key and try again.")
            }
        }
    }

    private fun showRoute(key: String, origin: String, destination: String) {
        if (key.isBlank()) { toast("Add your Google API key first"); return }
        if (origin.isBlank() || destination.isBlank()) { toast("Enter a start and a destination"); return }
        directionsOutput.text = "Getting directions..."
        val call = "route(${JSONObject.quote(origin)},${JSONObject.quote(destination)})"
        if (loadedKey != key) {
            loadedKey = key
            mapView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    view?.evaluateJavascript(call, null)
                }
            }
            mapView.loadDataWithBaseURL(
                "https://maps.local/", mapHtml.replace("__KEY__", enc(key)),
                "text/html", "utf-8", null
            )
        } else {
            mapView.evaluateJavascript(call, null)
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ---------- UI ----------

    private fun buildUi() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(40))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(BG))
            isFillViewport = true
            addView(col)
        }
        setContentView(scroll)

        col.addView(label("CHEF SPEAK", 30f, Color.WHITE, bold = true))
        col.addView(label("Text to speech, served hot.", 14f, Color.parseColor(MUTED)).also { gap(it, 4, 24) })

        // --- Text to speech section
        col.addView(sectionTitle("SAY IT"))
        val input = edit("Type something to say...", multiline = true)
        input.setText(prefs.getString("text", "This is raw! Get it back in the oven!"))
        col.addView(input)

        val speakBtn = primaryButton("SPEAK") {
            prefs.edit().putString("text", input.text.toString()).apply()
            val t = input.text.toString().trim()
            if (t.isNotEmpty()) speak(t)
        }
        col.addView(speakBtn)
        col.addView(ghostButton("STOP") { stopAll() })

        // --- Voice section
        col.addView(sectionTitle("VOICE"))
        statusView = label("", 13f, Color.WHITE)
        col.addView(statusView)
        sourceBtn = ghostButton("") {
            prefs.edit().putBoolean("cloud", !prefs.getBoolean("cloud", true)).apply()
            refreshStatus()
        }
        col.addView(sourceBtn)
        col.addView(ghostButton("INSTALL PHONE VOICE ENGINE") {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.tts")))
            } catch (e: Exception) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.tts")))
            }
        })
        col.addView(ghostButton("RETRY PHONE VOICE") { initTts(preferGoogle = true) })
        refreshStatus()
        col.addView(slider("Pitch", pitch, 0.5f, 1.5f) { v ->
            pitch = v; prefs.edit().putFloat("pitch", v).apply()
        })
        col.addView(slider("Speed", rate, 0.5f, 2.0f) { v ->
            rate = v; prefs.edit().putFloat("rate", v).apply()
        })
        col.addView(label(
            "Uses a British voice from your phone's text-to-speech engine. " +
                "For the best result, install Google Speech Services and pick a British male voice.",
            12f, Color.parseColor(MUTED)
        ).also { gap(it, 4, 0) })

        // --- Maps section
        col.addView(sectionTitle("MAPS (OPTIONAL)"))
        col.addView(label(
            "Add your own Google API key (Maps JavaScript API and Directions API enabled) to see the map and hear spoken directions. Don't restrict the key by HTTP referrer.",
            12f, Color.parseColor(MUTED)
        ))
        val keyField = edit("Google API key", password = true)
        keyField.setText(prefs.getString("apikey", ""))
        keyField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(c: CharSequence?, a: Int, b: Int, d: Int) {}
            override fun onTextChanged(c: CharSequence?, a: Int, b: Int, d: Int) {}
            override fun afterTextChanged(e: Editable?) {
                prefs.edit().putString("apikey", e.toString().trim()).apply()
                refreshStatus()
            }
        })
        col.addView(keyField)
        val from = edit("From (address or place)")
        val to = edit("To (address or place)")
        from.setText(prefs.getString("from", ""))
        to.setText(prefs.getString("to", ""))
        col.addView(from)
        col.addView(to)

        mapView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            setBackgroundColor(Color.BLACK)
            addJavascriptInterface(Bridge(), "Android")
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(340))
                .apply { topMargin = dp(12) }
        }
        col.addView(mapView)

        col.addView(primaryButton("SHOW ROUTE & SPEAK DIRECTIONS") {
            prefs.edit()
                .putString("apikey", keyField.text.toString().trim())
                .putString("from", from.text.toString())
                .putString("to", to.text.toString())
                .apply()
            showRoute(keyField.text.toString().trim(), from.text.toString().trim(), to.text.toString().trim())
        })
        col.addView(ghostButton("OPEN IN GOOGLE MAPS") {
            val dest = to.text.toString().trim()
            if (dest.isEmpty()) {
                toast("Enter a destination")
            } else {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${enc(dest)}")))
                } catch (e: Exception) {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=${enc(dest)}")))
                }
            }
        })

        directionsOutput = label("", 14f, Color.WHITE).also { gap(it, 16, 0) }
        col.addView(directionsOutput)
    }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    private fun gap(v: View, top: Int, bottom: Int) {
        val lp = (v.layoutParams as? LinearLayout.LayoutParams)
            ?: LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(top); lp.bottomMargin = dp(bottom)
        v.layoutParams = lp
    }

    private fun label(text: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun sectionTitle(text: String) = label(text, 12f, Color.parseColor(ACCENT), bold = true).also {
        it.letterSpacing = 0.15f
        gap(it, 32, 10)
    }

    private fun box(): GradientDrawable = GradientDrawable().apply {
        setColor(Color.parseColor(SURFACE))
        cornerRadius = dp(14).toFloat()
        setStroke(dp(1), Color.parseColor(BORDER))
    }

    private fun edit(hint: String, multiline: Boolean = false, password: Boolean = false) = EditText(this).apply {
        this.hint = hint
        setHintTextColor(Color.parseColor("#555555"))
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        background = box()
        setPadding(dp(14), dp(12), dp(14), dp(12))
        inputType = when {
            password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            else -> InputType.TYPE_CLASS_TEXT
        }
        if (multiline) { minLines = 4; gravity = Gravity.TOP }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(8) }
    }

    private fun primaryButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Typeface.DEFAULT_BOLD
        stateListAnimator = null
        background = GradientDrawable().apply {
            setColor(Color.parseColor(ACCENT)); cornerRadius = dp(14).toFloat()
        }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply { topMargin = dp(12) }
    }

    private fun ghostButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        stateListAnimator = null
        background = box()
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(8) }
    }

    private fun slider(name: String, initial: Float, min: Float, max: Float, onChange: (Float) -> Unit): View {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = label("$name: ${"%.2f".format(initial)}", 14f, Color.WHITE)
        val steps = 100
        val bar = SeekBar(this).apply {
            this.max = steps
            progress = (((initial - min) / (max - min)) * steps).toInt().coerceIn(0, steps)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = min + (max - min) * p / steps
                    title.text = "$name: ${"%.2f".format(v)}"
                    if (fromUser) onChange(v)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        wrap.addView(title)
        wrap.addView(bar)
        gap(wrap, 8, 0)
        return wrap
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        runCatching { player?.release() }
        super.onDestroy()
    }
}
