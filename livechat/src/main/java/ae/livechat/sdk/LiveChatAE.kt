package ae.livechat.sdk

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/**
 * livechat.ae chat for your Android app.
 *
 * ```kotlin
 * LiveChatAE.configure(context, key = "YOUR-WIDGET-KEY")
 * LiveChatAE.setVisitor(name = "Sara Rossi", email = "sara@example.com")
 * LiveChatAE.present(activity)
 * ```
 *
 * The chat is the same one your website visitors see, with the same settings, forms,
 * departments, languages and knowledge base. Call it from any thread: the work and every
 * event happen on the main thread.
 */
object LiveChatAE {
    /** Version of the SDK. The livechat.ae super admin sees it in the Versions tab. */
    const val VERSION = BuildConfig.SDK_VERSION

    // ---- Events ---------------------------------------------------------------------------

    /** Receives the chat events, on the main thread. Remove it when it goes away (an activity in `onDestroy`). */
    @JvmStatic
    fun addListener(listener: LiveChatAEListener) {
        ChatSession.listeners.add(listener)
    }

    @JvmStatic
    fun removeListener(listener: LiveChatAEListener) {
        ChatSession.listeners.remove(listener)
    }

    /** A new message arrived (agent, visitor or notice). */
    @JvmStatic var onMessage: ((LiveChatAEMessage) -> Unit)? = null

    /** The number of unread agent messages changed. */
    @JvmStatic var onUnreadCountChange: ((Int) -> Unit)? = null

    /** The chat window was shown. */
    @JvmStatic var onPresent: (() -> Unit)? = null

    /** The chat window was closed. */
    @JvmStatic var onDismiss: (() -> Unit)? = null

    /** A conversation started. */
    @JvmStatic var onChatStarted: ((String) -> Unit)? = null

    /** A conversation ended. */
    @JvmStatic var onChatEnded: ((String) -> Unit)? = null

    /** Something went wrong. */
    @JvmStatic var onError: ((LiveChatAEError) -> Unit)? = null

    // ---- Settings -------------------------------------------------------------------------

    /** How links and attachments tapped in the chat open. Default: [LiveChatAELinkHandling.InApp]. */
    @JvmStatic var linkHandling: LiveChatAELinkHandling = LiveChatAELinkHandling.InApp

    // ---- State ----------------------------------------------------------------------------

    /** `true` after [configure]. */
    @JvmStatic val isConfigured: Boolean get() = ChatSession.configuration != null

    /** The current configuration. */
    @JvmStatic val configuration: LiveChatAEConfiguration? get() = ChatSession.configuration

    /** Agent messages the visitor has not seen yet. */
    @JvmStatic val unreadCount: Int get() = ChatSession.unread

    /** `true` while the chat window is on screen. */
    @JvmStatic val isPresented: Boolean get() = ChatSession.isPresented

    /** `true` when at least one agent is online. */
    @JvmStatic val agentsOnline: Boolean get() = ChatSession.agentsOnline

    /** The last error, if any. */
    @JvmStatic val lastError: LiveChatAEError? get() = ChatSession.lastError

    // ---- Setup ----------------------------------------------------------------------------

    /**
     * Sets up the chat. Call it once at launch, in `Application.onCreate`: the chat loads in the
     * background, so that messages and the unread count arrive even before the visitor opens it.
     * Calling it again with different values reloads the chat.
     */
    @JvmStatic
    @JvmOverloads
    fun configure(
        context: Context,
        key: String,
        server: String = LiveChatAEConfiguration.DEFAULT_SERVER,
        language: String? = null,
        department: String? = null,
    ) = configure(context, LiveChatAEConfiguration(key, server, language, department))

    /** Sets up the chat. Calling it again with different values reloads the chat. */
    @JvmStatic
    fun configure(context: Context, configuration: LiveChatAEConfiguration) {
        val app = context.applicationContext
        val activity = context as? Activity
        onMain { ChatSession.configure(activity ?: app, configuration) }
    }

    // ---- Visitor --------------------------------------------------------------------------

    /**
     * Name and email of the signed-in user: they fill the pre-chat form and are used for
     * new chats when the form does not ask for them.
     */
    @JvmStatic
    fun setVisitor(name: String?, email: String?) = onMain { ChatSession.setVisitor(name, email) }

    /**
     * Custom fields shown to agents next to the chat (up to 20; keys up to 40 characters,
     * values up to 200). Same as `LiveChatAE.setVariables` on the website.
     */
    @JvmStatic
    fun setCustomFields(fields: Map<String, String>) {
        val copy = LinkedHashMap(fields)
        onMain { ChatSession.setCustomFields(copy) }
    }

    /**
     * The identity token signed by your server for the signed-in user (same as
     * `LiveChatAE.identify(token)` on the website); `null` to remove it, for example at logout.
     */
    @JvmStatic
    fun identify(token: String?) = onMain { ChatSession.identify(token) }

    /** Department for the next chats (its name). `null` lets the visitor choose. */
    @JvmStatic
    fun setDepartment(name: String?) = onMain { ChatSession.setDepartment(name) }

    /**
     * Forgets the visitor and the conversation on this device, for example when the user
     * signs out of your app. The next chat starts from scratch.
     */
    @JvmStatic
    fun reset() = onMain { ChatSession.reset() }

    // ---- Chat window ----------------------------------------------------------------------

    /**
     * Shows the chat. Without [activity], the SDK starts it from the activity on screen
     * (or, if there is none, in a new task).
     */
    @JvmStatic
    @JvmOverloads
    fun present(activity: Activity? = null) = onMain { ChatSession.present(activity) }

    /** Closes the chat window. The conversation goes on: new messages keep arriving. */
    @JvmStatic
    fun dismiss() = onMain { ChatSession.dismiss() }

    // ---- Push notifications ---------------------------------------------------------------

    /**
     * The Firebase Cloud Messaging registration token of the app (from `FirebaseMessaging.getToken()`
     * and `FirebaseMessagingService.onNewToken`). livechat.ae sends it to your server in the
     * `chat.agent_message` webhook, which sends the push. `null` stops the notifications for this device.
     */
    @JvmStatic
    fun setPushToken(token: String?) = onMain { ChatSession.setPushToken(token) }

    /**
     * `true` if the notification was sent by your server for a livechat.ae chat
     * (its data contains the `livechatae` key). Pass `RemoteMessage.data`.
     */
    @JvmStatic
    fun isLiveChatNotification(data: Map<String, String?>?): Boolean = data?.containsKey(PUSH_KEY) == true

    /**
     * `true` if the intent comes from a tap on a livechat.ae notification: its extras contain
     * the `livechatae` key (FCM puts the data of the message in the extras of the launch intent).
     */
    @JvmStatic
    fun isLiveChatNotification(intent: Intent?): Boolean = isLiveChatNotification(intent?.extras)

    /** `true` if the extras of a notification contain the `livechatae` key. */
    @JvmStatic
    fun isLiveChatNotification(extras: Bundle?): Boolean = extras?.containsKey(PUSH_KEY) == true

    /**
     * Opens the chat if the user tapped a livechat.ae notification. Call it from the `onCreate`
     * and `onNewIntent` of the activity that the notification opens; returns `false` (and does
     * nothing) for your other intents.
     */
    @JvmStatic
    @JvmOverloads
    fun handleNotification(intent: Intent?, activity: Activity? = null): Boolean {
        if (!isLiveChatNotification(intent)) return false
        intent?.removeExtra(PUSH_KEY)   // a recreated activity does not open the chat again
        present(activity)
        return true
    }

    /** Opens the chat if the data is from a livechat.ae notification (`RemoteMessage.data`). */
    @JvmStatic
    @JvmOverloads
    fun handleNotification(data: Map<String, String?>?, activity: Activity? = null): Boolean {
        if (!isLiveChatNotification(data)) return false
        present(activity)
        return true
    }

    /** The key that marks the push notifications of livechat.ae (in the data of the FCM message). */
    const val PUSH_KEY = "livechatae"

    private val main by lazy { Handler(Looper.getMainLooper()) }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
}
