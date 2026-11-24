package ae.livechat.sdk

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowInsets
import android.webkit.WebView
import android.widget.FrameLayout

/**
 * The chat window: full screen, with the chat web view of [ChatSession]. Opened by
 * [LiveChatAE.present]; the back button, the – button of the chat header and
 * [LiveChatAE.dismiss] close it. The conversation goes on after it closes.
 */
class LiveChatAEActivity : Activity() {
    private lateinit var root: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this)
        setContentView(root)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // edge to edge on every Android version: the chat stays between the system bars and above the keyboard
            @Suppress("DEPRECATION") // still needed below Android 15, where edge to edge is not the default
            window.setDecorFitsSystemWindows(false)
            root.setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime()
                )
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                WindowInsets.CONSUMED
            }
        }
        if (!ChatSession.attach(this)) finish()   // not configured (the process was restarted)
    }

    internal fun attach(webView: WebView) {
        (webView.parent as? ViewGroup)?.removeView(webView)
        // no requestFocus(): the keyboard opens when the user taps the message box, not by itself
        root.addView(webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    override fun onPause() {
        super.onPause()
        if (isFinishing) ChatSession.closing(this)   // tell the app now, not after the animation
    }

    override fun onDestroy() {
        ChatSession.detach(this, isFinishing)
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == PICK_FILE) ChatSession.deliverFiles(resultCode, data)
        else @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
    }

    internal companion object {
        const val PICK_FILE = 0x4C43
    }
}
