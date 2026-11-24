package ae.livechat.example

import ae.livechat.sdk.LiveChatAE
import ae.livechat.sdk.LiveChatAEButton
import ae.livechat.sdk.LiveChatAEError
import ae.livechat.sdk.LiveChatAEListener
import ae.livechat.sdk.LiveChatAEMessage
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Every feature of the SDK on one screen: account, visitor, open the chat, the chat button
 * with the unread badge, and the events as they arrive.
 */
class MainActivity : Activity(), LiveChatAEListener {
    private lateinit var status: TextView
    private lateinit var log: TextView
    private val lines = ArrayDeque<String>()
    private val time = SimpleDateFormat("HH:mm:ss", Locale.ROOT)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyExtras(intent)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(96))
        }
        column.addView(text("livechat.ae", 26f, bold = true))
        column.addView(text("Android SDK ${LiveChatAE.VERSION} — example", 14f, color = GREY))
        status = text("", 14f).also { column.addView(it, margins(top = 8)) }

        // ---- Account
        column.addView(section("Account"))
        val server = field("Server", Settings.server(this), InputType.TYPE_TEXT_VARIATION_URI)
        val key = field("Widget key", Settings.key(this))
        val language = field("Language (it, en, es, fr, de, ar, ru)", Settings.language(this))
        val department = field("Department", Settings.department(this))
        listOf(server, key, language, department).forEach { column.addView(it) }
        column.addView(button("Apply") {
            Settings.save(this, server.value(), key.value(), language.value(), department.value())
            Settings.configure(this)
            event("configured: ${LiveChatAE.configuration?.chatUrl}")
        })

        // ---- Visitor
        column.addView(section("Signed-in user"))
        val name = field("Name", "Sara Rossi", InputType.TYPE_TEXT_VARIATION_PERSON_NAME)
        val email = field("Email", "sara@example.com", InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val plan = field("Custom field \"plan\"", "Gold")
        listOf(name, email, plan).forEach { column.addView(it) }
        column.addView(row(
            button("Set user") {
                LiveChatAE.setVisitor(name.value(), email.value())
                LiveChatAE.setCustomFields(mapOf("plan" to plan.value(), "platform" to "Android"))
                event("visitor: ${name.value()} <${email.value()}>")
            },
            button("Sign out") {
                LiveChatAE.identify(null)
                LiveChatAE.reset()
                event("reset: visitor and conversation forgotten")
            },
        ))

        // ---- Push: a real app passes its Firebase Cloud Messaging token (see the README)
        column.addView(section("Push notifications"))
        val push = field("FCM token", "")
        column.addView(push)
        column.addView(button("Set push token") {
            LiveChatAE.setPushToken(push.value().ifEmpty { null })
            event(if (push.value().isEmpty()) "push token removed" else "push token set")
        })

        // ---- Chat
        column.addView(section("Chat"))
        column.addView(button("Open the chat") { LiveChatAE.present(this) }.apply { tag = "open-chat" })
        column.addView(button("Department: Sales") { LiveChatAE.setDepartment("Sales"); event("department: Sales") })

        column.addView(section("Events"))
        log = text("", 13f).apply { typeface = Typeface.MONOSPACE }
        column.addView(log)

        val root = FrameLayout(this).apply { fitsSystemWindows = true }
        root.addView(ScrollView(this).apply { addView(column) }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(LiveChatAEButton(this), FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
            setMargins(dp(20), dp(20), dp(20), dp(20))
        })
        setContentView(root)

        LiveChatAE.addListener(this)
        updateStatus()
        LiveChatAE.handleNotification(intent, this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (applyExtras(intent)) recreate()
        LiveChatAE.handleNotification(intent, this)
    }

    override fun onDestroy() {
        LiveChatAE.removeListener(this)
        super.onDestroy()
    }

    /** `adb shell am start -n ae.livechat.example/.MainActivity --es server http://10.0.2.2:8083 --es key demo` */
    private fun applyExtras(intent: Intent?): Boolean {
        val server = intent?.getStringExtra("server") ?: return false
        Settings.save(this, server, intent.getStringExtra("key") ?: Settings.key(this),
            intent.getStringExtra("language") ?: Settings.language(this), intent.getStringExtra("department") ?: Settings.department(this))
        Settings.configure(this)
        intent.removeExtra("server")
        return true
    }

    // ---- Events

    override fun onMessage(message: LiveChatAEMessage) {
        val who = when (message.sender) {
            LiveChatAEMessage.Sender.AGENT -> message.author.ifEmpty { "agent" }
            LiveChatAEMessage.Sender.VISITOR -> "you"
            else -> message.sender.name.lowercase()
        }
        event("message from $who: ${message.text}" + (message.attachment?.let { " [${it.name}]" } ?: ""))
    }

    override fun onUnreadCountChanged(count: Int) { event("unread: $count"); updateStatus() }
    override fun onChatPresented() = event("chat window opened")
    override fun onChatDismissed() = event("chat window closed")
    override fun onChatStarted(chatId: String) = event("chat started: ${chatId.take(8)}")
    override fun onChatEnded(chatId: String) = event("chat ended: ${chatId.take(8)}")
    override fun onAgentsOnlineChanged(online: Boolean) { event(if (online) "agents online" else "agents offline"); updateStatus() }
    override fun onError(error: LiveChatAEError) { event("error: $error"); updateStatus() }

    private fun updateStatus() {
        val online = if (LiveChatAE.agentsOnline) "● Agents online" else "○ Agents offline"
        status.text = "$online · Unread: ${LiveChatAE.unreadCount}"
        status.setTextColor(if (LiveChatAE.agentsOnline) Color.parseColor("#16A34A") else GREY)
    }

    private fun event(s: String) {
        lines.addFirst("${time.format(Date())}  $s")
        while (lines.size > 40) lines.removeLast()
        log.text = lines.joinToString("\n")
    }

    // ---- Small view helpers

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun margins(top: Int = 0) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun text(s: String, size: Float, bold: Boolean = false, color: Int = Color.parseColor("#0F172A")) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun section(title: String) = text(title.uppercase(), 12f, bold = true, color = GREY).apply {
        letterSpacing = 0.08f
        layoutParams = margins(top = 24)
        setPadding(0, 0, 0, dp(4))
    }

    private fun field(hint: String, value: String, type: Int = InputType.TYPE_CLASS_TEXT) = EditText(this).apply {
        this.hint = hint
        setText(value)
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT or type
        textSize = 15f
    }

    private fun EditText.value() = text.toString().trim()

    private fun button(title: String, action: () -> Unit) = Button(this).apply {
        text = title
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)) }
    }

    private companion object {
        val GREY = Color.parseColor("#64748B")
    }
}
