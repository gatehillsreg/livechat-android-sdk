package ae.livechat.sdk

import org.json.JSONObject
import java.util.Calendar
import java.util.Date
import java.util.TimeZone

/** The events that the chat page (widget.js in "sdk" mode) posts to the app. */
internal sealed class BridgeEvent {
    data class Ready(val online: Boolean, val chatId: String?, val unread: Int) : BridgeEvent()
    data class Status(val online: Boolean) : BridgeEvent()
    data class Message(val message: LiveChatAEMessage) : BridgeEvent()
    data class Unread(val count: Int) : BridgeEvent()
    data class ChatStarted(val chatId: String) : BridgeEvent()
    data class ChatClosed(val chatId: String) : BridgeEvent()
    data object CloseRequested : BridgeEvent()
    data class Error(val error: LiveChatAEError) : BridgeEvent()

    companion object {
        /** Reads what `window.LiveChatAEAndroid.postMessage(JSON.stringify(event))` sends. */
        fun parse(json: String?): BridgeEvent? {
            val d = try {
                JSONObject(json ?: return null)
            } catch (e: Exception) {
                return null
            }
            return when (d.optString("type")) {
                "ready" -> Ready(d.optBoolean("online"), string(d, "chatId"), int(d, "unread") ?: 0)
                "status" -> Status(d.optBoolean("online"))
                "unread" -> Unread(maxOf(0, int(d, "count") ?: 0))
                "chat_started" -> ChatStarted(string(d, "chatId") ?: "")
                "chat_closed" -> ChatClosed(string(d, "chatId") ?: "")
                "close_requested" -> CloseRequested
                "error" -> Error(LiveChatAEError.of(string(d, "code") ?: "unknown"))
                "message" -> {
                    val a = d.optJSONObject("attachment")
                    val attachment = a?.let {
                        LiveChatAEMessage.Attachment(string(it, "name") ?: "", string(it, "url"), string(it, "contentType") ?: "")
                    }
                    val id = (d.opt("id") as? Number)?.toLong()
                    Message(
                        LiveChatAEMessage(
                            id = id,
                            sender = LiveChatAEMessage.Sender.of(string(d, "sender")),
                            author = string(d, "author") ?: "",
                            text = string(d, "text") ?: "",
                            attachment = attachment,
                            date = date(d.opt("createdAt")),
                        )
                    )
                }
                else -> null
            }
        }

        private fun string(d: JSONObject, name: String): String? =
            if (!d.has(name) || d.isNull(name)) null else d.opt(name)?.toString()

        private fun int(d: JSONObject, name: String): Int? = when (val v = d.opt(name)) {
            is Number -> v.toInt()
            is String -> v.toIntOrNull()
            else -> null
        }

        private val ISO = Regex("""(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2}):(\d{2})(?:\.(\d+))?(Z|[+-]\d{2}:?\d{2})?""")

        /** The server sends ISO-8601 dates ("2026-11-24T06:30:00.123456Z") or epoch seconds. */
        fun date(v: Any?): Date? {
            if (v is Number) return Date((v.toDouble() * 1000).toLong())
            val m = ISO.matchEntire((v as? String)?.trim() ?: return null) ?: return null
            val g = m.groupValues
            val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            c.clear()
            c.set(g[1].toInt(), g[2].toInt() - 1, g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toInt())
            val millis = g[7].padEnd(3, '0').take(3).toInt()
            var t = c.timeInMillis + millis
            val zone = g[8]
            if (zone.isNotEmpty() && zone != "Z") {
                val sign = if (zone[0] == '-') -1 else 1
                val digits = zone.substring(1).replace(":", "")
                val offset = digits.substring(0, 2).toInt() * 60 + digits.substring(2, 4).toInt()
                t -= sign * offset * 60_000L
            }
            return Date(t)
        }
    }
}

/** JavaScript that the app runs in the chat page: calls to the widget API (window.LiveChatAE). */
internal object Script {
    /** `window.LiveChatAE.name(argument)`, or a queued call if the widget is not loaded yet. */
    fun call(name: String, argument: Any? = null): String {
        val arg = if (argument == null) "undefined" else json(argument)
        val n = json(name)
        return "(function(){var a=$arg;var w=window.LiveChatAE;" +
            "if(w&&!Array.isArray(w)&&typeof w[$n]==='function'){w[$n](a);}" +
            "else{(window.LiveChatAE=window.LiveChatAE||[]).push([$n,a]);}})();"
    }

    /** The calls that run before the widget loads (window.LiveChatAE as a queue). */
    fun queue(calls: List<Pair<String, Any>>): String =
        "window.LiveChatAE=[" + calls.joinToString(",") { "[${json(it.first)},${json(it.second)}]" } + "];"

    /** Forgets the conversation saved by the widget, before it starts (see [LiveChatAE.reset]). */
    const val CLEAR_STORAGE = "try{localStorage.clear();sessionStorage.clear();}catch(e){}"

    /** A JSON literal: safe to put in JavaScript whatever the text contains. */
    fun json(value: Any?): String = when (value) {
        null -> "null"
        is String -> quote(value)
        is Boolean -> value.toString()
        is Int, is Long, is Short, is Byte -> value.toString()
        is Number -> value.toDouble().let { if (it.isFinite()) value.toString() else "null" }
        is Map<*, *> -> value.entries
            .sortedBy { it.key.toString() }
            .joinToString(",", "{", "}") { quote(it.key.toString()) + ":" + json(it.value) }
        is Iterable<*> -> value.joinToString(",", "[", "]") { json(it) }
        is Array<*> -> value.joinToString(",", "[", "]") { json(it) }
        else -> quote(value.toString())
    }

    private fun quote(s: String): String {
        val b = StringBuilder(s.length + 2).append('"')
        for (c in s) {
            when (c) {
                '"' -> b.append("\\\"")
                '\\' -> b.append("\\\\")
                '/' -> b.append("\\/")          // "</script>" never closes anything
                '\n' -> b.append("\\n")
                '\r' -> b.append("\\r")
                '\t' -> b.append("\\t")
                '\b' -> b.append("\\b")
                '\u000C' -> b.append("\\f")
                '\u2028', '\u2029' -> b.append(unicode(c))   // line ends in older engines
                else -> if (c < ' ') b.append(unicode(c)) else b.append(c)
            }
        }
        return b.append('"').toString()
    }

    private fun unicode(c: Char) = "\\u" + Integer.toHexString(c.code).padStart(4, '0')
}
