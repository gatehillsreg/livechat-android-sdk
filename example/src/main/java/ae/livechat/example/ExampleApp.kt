package ae.livechat.example

import ae.livechat.sdk.LiveChatAE
import android.app.Application
import android.content.Context

class ExampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // once, at launch: the chat loads in the background and the unread count arrives at once
        Settings.configure(this)
    }
}

/** What the Account section of the example saves. */
object Settings {
    /** The computer that runs the emulator, seen from the emulator. */
    const val DEFAULT_SERVER = "http://10.0.2.2:8080"
    const val DEFAULT_KEY = "demo"

    private fun prefs(c: Context) = c.getSharedPreferences("example", Context.MODE_PRIVATE)

    fun server(c: Context) = prefs(c).getString("server", DEFAULT_SERVER)!!
    fun key(c: Context) = prefs(c).getString("key", DEFAULT_KEY)!!
    fun language(c: Context) = prefs(c).getString("language", "")!!
    fun department(c: Context) = prefs(c).getString("department", "")!!

    fun save(c: Context, server: String, key: String, language: String, department: String) {
        prefs(c).edit()
            .putString("server", server.trim())
            .putString("key", key.trim())
            .putString("language", language.trim())
            .putString("department", department.trim())
            .apply()
    }

    fun configure(c: Context) {
        LiveChatAE.configure(
            c,
            key = key(c),
            server = server(c),
            language = language(c).ifEmpty { null },
            department = department(c).ifEmpty { null },
        )
    }
}
