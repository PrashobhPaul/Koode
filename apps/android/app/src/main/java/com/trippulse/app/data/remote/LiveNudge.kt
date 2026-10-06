package com.trippulse.app.data.remote

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The live nudge, heard: a listener on a journey's Supabase Realtime channel.
 *
 * Each time the traveller's phone writes its live state, a data-free "fix"
 * message is broadcast on the journey's own channel. A follower who hears it
 * reads the state at once through the same approved read as always, instead
 * of waiting for the next poll. The message carries no position, the channel
 * name only ever arrives inside the state an approved reader already has, and
 * when Realtime cannot be reached the poll carries on as before.
 *
 * Speaks the Phoenix protocol Realtime uses: join the topic, heartbeat every
 * 25 s, and treat `broadcast` / `fix` as the nudge.
 */
class LiveNudge(private val baseUrl: String, private val anonKey: String, base: OkHttpClient) {

    private val client: OkHttpClient = base.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    /** One Unit per nudge on [channel]; reconnects with backoff while collected. */
    fun nudges(channel: String): Flow<Unit> = callbackFlow {
        val url = baseUrl.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") +
            "/realtime/v1/websocket?apikey=$anonKey&vsn=1.0.0"
        val topic = "realtime:koode:$channel"
        var ref = 0
        val socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(join(topic, ++ref))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (isNudge(text)) trySend(Unit)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                close(IOException("live channel closed: $code"))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                close(t)
            }
        })
        val beat = launch {
            while (isActive) {
                delay(HEARTBEAT_MS)
                socket.send(JSONObject().put("topic", "phoenix").put("event", "heartbeat")
                    .put("payload", JSONObject()).put("ref", (++ref).toString()).toString())
            }
        }
        awaitClose {
            beat.cancel()
            socket.close(1000, null)
        }
    }.retryWhen { _, attempt ->
        delay((2_000L shl attempt.toInt().coerceAtMost(5)).coerceAtMost(60_000L))
        true
    }

    companion object {
        const val HEARTBEAT_MS = 25_000L

        fun join(topic: String, ref: Int): String = JSONObject()
            .put("topic", topic)
            .put("event", "phx_join")
            .put("ref", ref.toString())
            .put("join_ref", "1")
            .put("payload", JSONObject().put("config", JSONObject()
                .put("broadcast", JSONObject().put("ack", false).put("self", false))
                .put("presence", JSONObject().put("key", ""))
                .put("private", false)))
            .toString()

        /** A Realtime frame is a nudge when it is a broadcast of the "fix" event. */
        fun isNudge(frame: String): Boolean = runCatching {
            val o = JSONObject(frame)
            o.optString("event") == "broadcast" && o.optJSONObject("payload")?.optString("event") == "fix"
        }.getOrDefault(false)
    }
}
