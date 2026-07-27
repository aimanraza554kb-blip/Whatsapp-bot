package com.myra.assistant.gemini

import android.util.Base64
import com.myra.assistant.data.model.ConnectionState
import com.myra.assistant.util.Constants
import com.myra.assistant.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WebSocket client for the Gemini Live API (BidiGenerateContent). Handles setup,
 * streaming PCM audio in/out, input/output transcription, interruptions,
 * automatic reconnect with backoff, keepalive pings and periodic session renewal.
 */
class GeminiLiveClient(
    private val scope: CoroutineScope,
    private val onEvent: (GeminiEvent) -> Unit
) {

    private val http = OkHttpClient.Builder()
        .pingInterval(Constants.HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var config: GeminiConfig? = null
    private val running = AtomicBoolean(false)
    private var reconnectAttempts = 0
    private var renewJob: Job? = null

    fun connect(config: GeminiConfig) {
        this.config = config
        running.set(true)
        reconnectAttempts = 0
        scope.launch {
            resolveWorkingModel(config)
            openSocket()
        }
    }

    /**
     * Query the REST ListModels endpoint so we connect with a model this API key
     * actually supports for bidiGenerateContent (the Live API). Different keys and
     * projects expose different Live models, so we auto-pick a working one instead
     * of hard-coding a name that may be unavailable for the user.
     */
    private fun resolveWorkingModel(cfg: GeminiConfig) {
        if (cfg.apiKey.isBlank()) return
        val cached = cachedModel
        if (cached != null && cachedKey == cfg.apiKey) {
            if (cached != cfg.model) config = cfg.copy(model = cached)
            return
        }
        try {
            val req = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000&key=" + cfg.apiKey)
                .build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: return
                val models = JSONObject(body).optJSONArray("models") ?: return
                val bidi = ArrayList<String>()
                for (i in 0 until models.length()) {
                    val m = models.getJSONObject(i)
                    val methods = m.optJSONArray("supportedGenerationMethods") ?: continue
                    for (j in 0 until methods.length()) {
                        if (methods.getString(j).equals("bidiGenerateContent", true)) {
                            bidi.add(m.getString("name").removePrefix("models/"))
                        }
                    }
                }
                Logger.i(TAG, "Live-capable models for this key: $bidi")
                if (bidi.isEmpty()) {
                    onEvent(GeminiEvent.Error("This API key has no Live (bidiGenerateContent) models enabled."))
                    return
                }
                val chosen = if (bidi.any { it == cfg.model }) cfg.model else bidi.first()
                if (chosen != cfg.model) {
                    config = cfg.copy(model = chosen)
                    Logger.i(TAG, "Model ${cfg.model} unavailable; switching to $chosen")
                }
                cachedKey = cfg.apiKey
                cachedModel = chosen
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Model resolution failed", e)
        }
    }

    private fun openSocket() {
        val cfg = config ?: return
        if (cfg.apiKey.isBlank()) {
            onEvent(GeminiEvent.Error("Gemini API key is missing. Add it in Settings."))
            return
        }
        val url = Constants.GEMINI_WS_HOST + "?key=" + cfg.apiKey
        val request = Request.Builder().url(url).build()
        onEvent(GeminiEvent.StateChanged(ConnectionState.CONNECTING))
        webSocket = http.newWebSocket(request, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            Logger.i(TAG, "WebSocket open")
            reconnectAttempts = 0
            sendSetup(ws)
            scheduleRenew()
            onEvent(GeminiEvent.Connected)
        }

        override fun onMessage(ws: WebSocket, text: String) = handleMessage(text)

        override fun onMessage(ws: WebSocket, bytes: ByteString) = handleMessage(bytes.utf8())

        override fun onClosing(ws: WebSocket, code: Int, reason: String) {
            ws.close(NORMAL_CLOSURE, null)
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            Logger.i(TAG, "WebSocket closed: $code $reason")
            if (code != NORMAL_CLOSURE && reason.isNotBlank()) {
                onEvent(GeminiEvent.Error("Server closed ($code): $reason"))
            }
            if (running.get()) reconnect() else onEvent(GeminiEvent.Closed)
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            val detail = buildString {
                append(t.message ?: "Connection failed")
                response?.let { r ->
                    append(" (HTTP ").append(r.code).append(")")
                    try {
                        r.body?.string()?.takeIf { it.isNotBlank() }?.let { append(": ").append(it.take(300)) }
                    } catch (_: Exception) {
                    }
                }
            }
            Logger.e(TAG, "WebSocket failure: $detail", t)
            onEvent(GeminiEvent.Error(detail))
            if (running.get()) reconnect()
        }
    }

    private fun sendSetup(ws: WebSocket) {
        val cfg = config ?: return
        val speechConfig = JSONObject().put(
            "voiceConfig",
            JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", cfg.voiceName))
        )
        val generationConfig = JSONObject()
            .put("responseModalities", JSONArray().put("AUDIO"))
            .put("speechConfig", speechConfig)

        val setup = JSONObject()
            .put("model", "models/" + cfg.model)
            .put("generationConfig", generationConfig)
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", cfg.systemInstruction))))
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
            .put("realtimeInputConfig", JSONObject().put("automaticActivityDetection", JSONObject()))

        cfg.toolsJson?.takeIf { it.isNotBlank() }?.let { setup.put("tools", JSONArray(it)) }

        val message = JSONObject().put("setup", setup)
        ws.send(message.toString())
        Logger.d(TAG, "Setup sent for model ${cfg.model}")
    }

    /** Stream a chunk of 16kHz mono PCM16 microphone audio to Gemini. */
    fun sendAudio(pcm: ByteArray) {
        val ws = webSocket ?: return
        val b64 = Base64.encodeToString(pcm, Base64.NO_WRAP)
        val chunk = JSONObject()
            .put("mimeType", "audio/pcm;rate=" + Constants.INPUT_SAMPLE_RATE)
            .put("data", b64)
        val message = JSONObject().put(
            "realtimeInput",
            JSONObject().put("mediaChunks", JSONArray().put(chunk))
        )
        ws.send(message.toString())
    }

    /** Send a typed text turn (used by the chat input box). */
    fun sendText(text: String) {
        val ws = webSocket ?: return
        val turn = JSONObject()
            .put("role", "user")
            .put("parts", JSONArray().put(JSONObject().put("text", text)))
        val message = JSONObject().put(
            "clientContent",
            JSONObject().put("turns", JSONArray().put(turn)).put("turnComplete", true)
        )
        ws.send(message.toString())
    }

    /** Send function-call results back to the model so it can finish the turn. */
    fun sendToolResponse(responses: List<GeminiFunctionResponse>) {
        val ws = webSocket ?: return
        if (responses.isEmpty()) return
        val arr = JSONArray()
        responses.forEach { r ->
            arr.put(
                JSONObject()
                    .put("id", r.id)
                    .put("name", r.name)
                    .put("response", JSONObject().put("result", r.result))
            )
        }
        val message = JSONObject().put("toolResponse", JSONObject().put("functionResponses", arr))
        ws.send(message.toString())
    }

    private fun handleMessage(raw: String) {
        try {
            val obj = JSONObject(raw)
            if (obj.has("setupComplete")) {
                onEvent(GeminiEvent.SetupComplete)
                return
            }
            if (obj.has("toolCall")) {
                val fcs = obj.getJSONObject("toolCall").optJSONArray("functionCalls")
                if (fcs != null) {
                    val calls = ArrayList<GeminiFunctionCall>()
                    for (i in 0 until fcs.length()) {
                        val c = fcs.getJSONObject(i)
                        calls.add(
                            GeminiFunctionCall(
                                c.optString("id"),
                                c.optString("name"),
                                c.optJSONObject("args") ?: JSONObject()
                            )
                        )
                    }
                    if (calls.isNotEmpty()) onEvent(GeminiEvent.ToolCall(calls))
                }
                return
            }
            if (obj.has("serverContent")) {
                val sc = obj.getJSONObject("serverContent")
                if (sc.optBoolean("interrupted", false)) onEvent(GeminiEvent.Interrupted)
                sc.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }
                    ?.let { onEvent(GeminiEvent.InputTranscript(it)) }
                sc.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }
                    ?.let { onEvent(GeminiEvent.OutputTranscript(it)) }
                sc.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
                    for (i in 0 until parts.length()) {
                        val part = parts.getJSONObject(i)
                        part.optJSONObject("inlineData")?.let { data ->
                            val mime = data.optString("mimeType", "")
                            if (mime.startsWith("audio")) {
                                val pcm = Base64.decode(data.getString("data"), Base64.NO_WRAP)
                                onEvent(GeminiEvent.AudioChunk(pcm))
                            }
                        }
                        part.optString("text").takeIf { it.isNotEmpty() }
                            ?.let { onEvent(GeminiEvent.OutputTranscript(it)) }
                    }
                }
                if (sc.optBoolean("turnComplete", false)) onEvent(GeminiEvent.TurnComplete)
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to parse message", e)
        }
    }

    private fun reconnect() {
        onEvent(GeminiEvent.StateChanged(ConnectionState.RECONNECTING))
        renewJob?.cancel()
        scope.launch {
            val delayMs = (Constants.RECONNECT_BASE_DELAY_MS * (1L shl reconnectAttempts.coerceAtMost(5)))
                .coerceAtMost(Constants.RECONNECT_MAX_DELAY_MS)
            reconnectAttempts++
            Logger.i(TAG, "Reconnecting in ${delayMs}ms (attempt $reconnectAttempts)")
            delay(delayMs)
            if (running.get()) openSocket()
        }
    }

    private fun scheduleRenew() {
        renewJob?.cancel()
        renewJob = scope.launch {
            delay(Constants.SESSION_RENEW_MS)
            if (running.get()) {
                Logger.i(TAG, "Renewing session")
                webSocket?.close(NORMAL_CLOSURE, "renew")
            }
        }
    }

    fun close() {
        running.set(false)
        renewJob?.cancel()
        webSocket?.close(NORMAL_CLOSURE, "client closed")
        webSocket = null
        onEvent(GeminiEvent.StateChanged(ConnectionState.IDLE))
    }

    companion object {
        private const val TAG = "GeminiLiveClient"
        private const val NORMAL_CLOSURE = 1000
        // Cache the Live-capable model resolved for a given API key so repeated
        // session starts skip the extra REST round-trip and connect faster.
        @Volatile private var cachedKey: String? = null
        @Volatile private var cachedModel: String? = null
    }
}
