package ae.livechat.sdk

import android.net.Uri
import java.net.URLEncoder
import java.util.Date

/** Where and how the chat runs. Pass it to [LiveChatAE.configure]. */
data class LiveChatAEConfiguration @JvmOverloads constructor(
    /**
     * The widget key of your livechat.ae account: the same `data-key` you put in the website
     * snippet (Admin › Installation).
     */
    val key: String,
    /** The livechat.ae server. Change it only for a self-hosted or local server. */
    val server: String = DEFAULT_SERVER,
    /**
     * Chat language: `it`, `en`, `es`, `fr`, `de`, `ar` or `ru`. `null` uses the language set in
     * your account, then the language of the device.
     */
    val language: String? = null,
    /**
     * Department that receives the chats started from the app (its name, as in Admin › Departments).
     * `null` lets the visitor choose when your account has more than one department.
     */
    val department: String? = null,
) {
    /** The page that the SDK opens in its web view, or `null` if the key or the server are not valid. */
    val chatUrl: String?
        get() {
            val base = serverBase ?: return null
            if (key.isBlank()) return null
            val q = StringBuilder("key=").append(encode(key))
            if (!language.isNullOrBlank()) q.append("&lang=").append(encode(language))
            if (!department.isNullOrBlank()) q.append("&department=").append(encode(department))
            return "$base/sdk/chat?$q"
        }

    /** `scheme://host[:port]` of the server: the only origin trusted by the bridge. */
    internal val origin: String?
        get() {
            val base = serverBase ?: return null
            val afterScheme = base.indexOf("://") + 3
            val slash = base.indexOf('/', afterScheme)
            return if (slash < 0) base else base.substring(0, slash)
        }

    private val serverBase: String?
        get() {
            val s = server.trim().trimEnd('/')
            val lower = s.lowercase()
            if (!lower.startsWith("https://") && !lower.startsWith("http://")) return null
            if (s.substringAfter("://").isEmpty() || s.contains('?') || s.contains('#')) return null
            return s
        }

    companion object {
        const val DEFAULT_SERVER = "https://livechat.ae"

        private fun encode(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }
}

/** A message of the conversation, as received by [LiveChatAEListener.onMessage]. */
data class LiveChatAEMessage(
    val id: Long?,
    val sender: Sender,
    /** Name of who wrote it (empty for the visitor and for notices). */
    val author: String,
    val text: String,
    val attachment: Attachment?,
    val date: Date?,
) {
    enum class Sender {
        /** An agent, an AI agent or an automation of your team. */
        AGENT,

        /** The visitor, that is the user of your app. */
        VISITOR,

        /** A notice of the chat (agent joined, chat closed...). */
        SYSTEM,
        UNKNOWN;

        internal companion object {
            fun of(s: String?) = entries.firstOrNull { it.name == s && it != UNKNOWN } ?: UNKNOWN
        }
    }

    data class Attachment(val name: String, val url: String?, val contentType: String)
}

/** What can go wrong. Delivered to [LiveChatAEListener.onError]. */
sealed class LiveChatAEError {
    /** [LiveChatAE.configure] has not been called. */
    data object NotConfigured : LiveChatAEError()

    /** The widget key does not exist or the account is not active. */
    data object InvalidKey : LiveChatAEError()

    /** The chat page could not be loaded (no network, server down...). */
    data class Network(val description: String) : LiveChatAEError()

    /** The server answered with an unexpected HTTP status. */
    data class Http(val status: Int) : LiveChatAEError()

    /** The chat settings could not be loaded. */
    data object ConfigurationFailed : LiveChatAEError()

    /** The account turned the chat off for this visitor. */
    data object Disabled : LiveChatAEError()

    /** A new chat could not be started. */
    data object ChatStartFailed : LiveChatAEError()

    /** Too many chats started from this network in a few minutes. */
    data object TooManyChats : LiveChatAEError()

    /** Any other error reported by the chat. */
    data class Other(val code: String) : LiveChatAEError()

    internal companion object {
        fun of(code: String): LiveChatAEError = when (code) {
            "config_failed" -> ConfigurationFailed
            "disabled" -> Disabled
            "chat_start_failed" -> ChatStartFailed
            "too_many_chats" -> TooManyChats
            "missing_key" -> InvalidKey
            else -> Other(code)
        }
    }
}

/** How links and attachments tapped in the chat are opened. */
sealed class LiveChatAELinkHandling {
    /** Inside the app, in a Custom Tab of the browser (default). */
    data object InApp : LiveChatAELinkHandling()

    /** Outside the app, in the browser or in the app that handles the link. */
    data object External : LiveChatAELinkHandling()

    /** Your code decides: the SDK calls [handler] and does nothing else. */
    class Custom(val handler: (Uri) -> Unit) : LiveChatAELinkHandling()
}

/**
 * Receives the chat events, on the main thread. Every method is optional.
 * Register it with [LiveChatAE.addListener].
 */
interface LiveChatAEListener {
    /** A new message: from an agent (also while the chat window is closed), from the visitor or a notice. */
    fun onMessage(message: LiveChatAEMessage) {}

    /** The number of agent messages not seen yet changed. */
    fun onUnreadCountChanged(count: Int) {}

    /** The chat window opened. */
    fun onChatPresented() {}

    /** The chat window closed. The conversation goes on. */
    fun onChatDismissed() {}

    /** The visitor sent the first message, reopened a closed chat, or an agent invited them. */
    fun onChatStarted(chatId: String) {}

    /** The conversation was closed by the agent, the visitor or for inactivity. */
    fun onChatEnded(chatId: String) {}

    /** Agents came online or went offline. */
    fun onAgentsOnlineChanged(online: Boolean) {}

    /** Something went wrong. */
    fun onError(error: LiveChatAEError) {}
}
