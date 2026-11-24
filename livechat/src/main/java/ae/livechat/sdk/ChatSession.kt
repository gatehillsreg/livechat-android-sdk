package ae.livechat.sdk

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.JsResult
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The one chat of the app: a WebView with the livechat.ae widget in "sdk" mode.
 *
 * The web view lives as long as the app, also while the chat window is closed (detached,
 * invisible): so the conversation stays connected, messages and the unread count arrive,
 * and opening the chat is instant. When the app goes to the background the connection
 * closes (for agents the visitor has left) and reopens on return.
 *
 * Everything here runs on the main thread.
 */
@SuppressLint("StaticFieldLeak") // only the Application context is kept; activities are weak
internal object ChatSession {
    var configuration: LiveChatAEConfiguration? = null
        private set
    var unread = 0
        private set
    var isPresented = false
        private set
    var agentsOnline = false
        private set
    var lastError: LiveChatAEError? = null
        private set

    val listeners = CopyOnWriteArraySet<LiveChatAEListener>()
    private val main = Handler(Looper.getMainLooper())

    // what the app said about the visitor: replayed to every page load
    private val visitor = LinkedHashMap<String, String>()
    private var fields: Map<String, String>? = null
    private var identity: String? = null
    private var department: String? = null
    private var departmentSet = false
    private var pushToken: String? = null
    private var clearStorage = false

    private var app: Application? = null
    private var webView: WebView? = null
    private var webContext: MutableContextWrapper? = null
    private var documentStart: ScriptHandler? = null
    private var pageReady = false
    private var loadFailed = false
    private var chatActivity: WeakReference<LiveChatAEActivity>? = null
    private var resumedActivity: WeakReference<Activity>? = null
    /** The activities between onStart and onStop (identity hash codes); empty = the app is in the background. */
    private val startedActivities = HashSet<Int>()
    private var inBackground = false
    internal var fileChooser: ValueCallback<Array<Uri>>? = null

    // ---- Setup ----------------------------------------------------------------------------

    fun configure(context: Context, c: LiveChatAEConfiguration) {
        attachApplication(context)
        if (c == configuration && webView != null && !loadFailed) return
        configuration = c
        if (!departmentSet) department = c.department
        if (c.chatUrl == null) {
            fail(LiveChatAEError.InvalidKey)
            return
        }
        load()
    }

    private fun attachApplication(context: Context) {
        if (app != null) return
        val a = context.applicationContext as Application
        app = a
        if (context is Activity) resumedActivity = WeakReference(context)
        a.registerActivityLifecycleCallbacks(Lifecycle)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun makeWebView(): WebView {
        val app = app!!
        val ctx = MutableContextWrapper(app)
        webContext = ctx
        val w = WebView(ctx)
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true                      // the conversation survives app restarts
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)               // target="_blank" and window.open → onCreateWindow
            allowFileAccess = false
            allowContentAccess = false
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = "$userAgentString livechat.ae-android-sdk/${LiveChatAE.VERSION}"
        }
        w.setBackgroundColor(Color.TRANSPARENT)
        w.isVerticalScrollBarEnabled = false
        w.isHorizontalScrollBarEnabled = false
        w.overScrollMode = WebView.OVER_SCROLL_NEVER
        CookieManager.getInstance().setAcceptCookie(true)
        if (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) WebView.setWebContentsDebuggingEnabled(true)
        installBridge(w)
        w.webViewClient = Client
        w.webChromeClient = Chrome
        return w
    }

    /** `window.LiveChatAEAndroid.postMessage(json)`, only for the chat page of the configured server. */
    private fun installBridge(w: WebView) {
        val origin = configuration?.origin
        if (origin != null && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(w, BRIDGE, setOf(origin)) { view, message, _, isMainFrame, _ ->
                if (isMainFrame && view === webView) handle(BridgeEvent.parse(message.data))
            }
        } else {
            w.addJavascriptInterface(JsBridge(w), BRIDGE)
        }
    }

    /** Fallback for old WebViews: called on a background thread, by any frame. */
    private class JsBridge(w: WebView) {
        private val view = WeakReference(w)

        @JavascriptInterface
        fun postMessage(json: String?) {
            val event = BridgeEvent.parse(json) ?: return
            Handler(Looper.getMainLooper()).post {
                val w = view.get()
                // no origin check in this API: at least the page must be the chat of the configured server
                if (w != null && w === webView && w.url?.let { Uri.parse(it).origin() } == configuration?.origin) handle(event)
            }
        }
    }

    private fun load() {
        val c = configuration ?: return
        val url = c.chatUrl ?: return
        var w = webView
        if (w != null && origin != c.origin) {  // another server: the bridge trusts one origin only
            destroyWebView()
            w = null
        }
        if (w == null) {
            w = makeWebView()
            webView = w
            origin = c.origin
            chatActivity?.get()?.let {
                webContext?.baseContext = it
                it.attach(w)
            }
        }
        pageReady = false
        loadFailed = false
        refreshDocumentStart()
        w.loadUrl(url)
    }

    private var origin: String? = null

    private fun queuedCalls(): List<Pair<String, Any>> {
        val calls = ArrayList<Pair<String, Any>>()
        if (visitor.isNotEmpty()) calls += "setVisitor" to HashMap(visitor)
        fields?.let { calls += "setVariables" to it }
        identity?.let { calls += "identify" to it }
        if (departmentSet) calls += "setDepartment" to (department ?: "")
        pushToken?.let { calls += "setPushToken" to mapOf("platform" to "android", "device" to it) }
        return calls
    }

    private val usesDocumentStart: Boolean
        get() = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    /**
     * What the app already knows goes in before the widget starts (window.LiveChatAE as a queue).
     * Old WebViews without document-start scripts get the same calls when the page is ready.
     */
    private fun refreshDocumentStart() {
        val w = webView ?: return
        val origin = configuration?.origin ?: return
        if (!usesDocumentStart) return
        documentStart?.remove()
        val script = (if (clearStorage) Script.CLEAR_STORAGE else "") + Script.queue(queuedCalls())
        documentStart = WebViewCompat.addDocumentStartJavaScript(w, script, setOf(origin))
    }

    private fun run(name: String, argument: Any? = null) {
        val w = webView ?: return
        if (!pageReady) return   // before "ready", the document-start script (or the replay) has it all
        w.evaluateJavascript(Script.call(name, argument), null)
    }

    // ---- Visitor --------------------------------------------------------------------------

    fun setVisitor(name: String?, email: String?) {
        visitor.clear()
        if (!name.isNullOrEmpty()) visitor["name"] = name
        if (!email.isNullOrEmpty()) visitor["email"] = email
        refreshDocumentStart()
        run("setVisitor", HashMap(visitor))
    }

    fun setCustomFields(f: Map<String, String>) {
        fields = LinkedHashMap(f)
        refreshDocumentStart()
        run("setVariables", f)
    }

    fun identify(token: String?) {
        identity = token?.takeIf { it.isNotEmpty() }
        refreshDocumentStart()
        run("identify", identity ?: "")
    }

    fun setDepartment(name: String?) {
        department = name?.takeIf { it.isNotEmpty() }
        departmentSet = true
        refreshDocumentStart()
        run("setDepartment", department ?: "")
    }

    fun setPushToken(token: String?) {
        pushToken = token?.takeIf { it.isNotEmpty() }
        refreshDocumentStart()
        run("setPushToken", mapOf("platform" to "android", "device" to (pushToken ?: "")))
    }

    fun reset() {
        visitor.clear()
        fields = null
        identity = null
        setUnread(0)
        val c = configuration ?: return
        val w = webView ?: return
        val url = c.chatUrl ?: return
        // cookies of the chat server
        val cookies = CookieManager.getInstance()
        cookies.getCookie(url)?.split(';')?.map { it.substringBefore('=').trim() }?.filter { it.isNotEmpty() }?.forEach {
            cookies.setCookie(url, "$it=; Max-Age=0; Path=/")
        }
        cookies.flush()
        c.origin?.let { WebStorage.getInstance().deleteOrigin(it) }
        if (usesDocumentStart) {
            clearStorage = true   // the next page starts with empty storage; off again when it has loaded
            load()
        } else {
            w.evaluateJavascript(Script.CLEAR_STORAGE) { load() }
        }
    }

    // ---- Window ---------------------------------------------------------------------------

    fun present(from: Activity?) {
        if (configuration == null) {
            fail(LiveChatAEError.NotConfigured)
            return
        }
        if (isPresented && chatActivity?.get() != null) return
        if (webView == null || loadFailed) load()
        val starter: Context = from ?: resumedActivity?.get() ?: app ?: return
        val intent = Intent(starter, LiveChatAEActivity::class.java)
        if (starter !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        isPresented = true
        starter.startActivity(intent)
        run("open")
        notify { it.onChatPresented() }
        LiveChatAE.onPresent?.invoke()
    }

    fun dismiss() {
        val a = chatActivity?.get()
        if (a != null && !a.isFinishing) a.finish() else didDismiss()
    }

    /** The chat activity is on screen (also after a recreation): it takes the web view. */
    fun attach(activity: LiveChatAEActivity): Boolean {
        val w = webView ?: return false
        chatActivity = WeakReference(activity)
        webContext?.baseContext = activity   // dialogs and the keyboard need an activity
        if (!isPresented) {                  // opened by the system (task restored), not by present()
            isPresented = true
            run("open")
            notify { it.onChatPresented() }
            LiveChatAE.onPresent?.invoke()
        }
        activity.attach(w)
        return true
    }

    /** The chat activity is going away; [finishing] when the window closes for good. */
    fun detach(activity: LiveChatAEActivity, finishing: Boolean) {
        if (chatActivity?.get() !== activity) return
        chatActivity = null
        (webView?.parent as? ViewGroup)?.removeView(webView)
        app?.let { webContext?.baseContext = it }
        fileChooser?.onReceiveValue(null)
        fileChooser = null
        if (finishing) didDismiss()
    }

    /** The chat activity is finishing: the window is closed for the app, the web view stays until it is gone. */
    fun closing(activity: LiveChatAEActivity) {
        if (chatActivity?.get() === activity) didDismiss()
    }

    private fun didDismiss() {
        if (!isPresented) return
        isPresented = false
        run("close")
        notify { it.onChatDismissed() }
        LiveChatAE.onDismiss?.invoke()
    }

    // ---- App lifecycle --------------------------------------------------------------------

    private object Lifecycle : Application.ActivityLifecycleCallbacks {
        // configure() may come after an activity started: that one is not in the set, and it does
        // not need to be, because the app is in the background when the set empties
        override fun onActivityStarted(activity: Activity) {
            startedActivities.add(System.identityHashCode(activity))
            if (inBackground) {
                inBackground = false
                appWillEnterForeground()
            }
        }

        override fun onActivityStopped(activity: Activity) {
            startedActivities.remove(System.identityHashCode(activity))
            if (startedActivities.isEmpty() && !activity.isChangingConfigurations && !inBackground) {
                inBackground = true
                appDidEnterBackground()
            }
        }

        override fun onActivityResumed(activity: Activity) {
            resumedActivity = WeakReference(activity)
        }

        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {
            if (resumedActivity?.get() === activity) resumedActivity = null
        }
    }

    private fun appDidEnterBackground() {
        run("pause")
    }

    private fun appWillEnterForeground() {
        if (loadFailed) {
            load()
            return
        }
        run("resume")
    }

    // ---- Events from the page -------------------------------------------------------------

    private fun handle(event: BridgeEvent?) {
        when (event) {
            null -> {}
            is BridgeEvent.Ready -> {
                pageReady = true
                loadFailed = false
                if (!usesDocumentStart) {
                    webView?.evaluateJavascript(queuedCalls().joinToString("") { Script.call(it.first, it.second) }, null)
                }
                setOnline(event.online)
                setUnread(event.unread)
                if (isPresented) run("open")
            }
            is BridgeEvent.Status -> setOnline(event.online)
            is BridgeEvent.Message -> {
                notify { it.onMessage(event.message) }
                LiveChatAE.onMessage?.invoke(event.message)
            }
            is BridgeEvent.Unread -> setUnread(event.count)
            is BridgeEvent.ChatStarted -> {
                notify { it.onChatStarted(event.chatId) }
                LiveChatAE.onChatStarted?.invoke(event.chatId)
            }
            is BridgeEvent.ChatClosed -> {
                notify { it.onChatEnded(event.chatId) }
                LiveChatAE.onChatEnded?.invoke(event.chatId)
            }
            BridgeEvent.CloseRequested -> dismiss()
            is BridgeEvent.Error -> fail(event.error)
        }
    }

    private fun setUnread(n: Int) {
        if (n == unread) return
        unread = n
        notify { it.onUnreadCountChanged(n) }
        LiveChatAE.onUnreadCountChange?.invoke(n)
    }

    private fun setOnline(online: Boolean) {
        if (online == agentsOnline) return
        agentsOnline = online
        notify { it.onAgentsOnlineChanged(online) }
    }

    private fun fail(e: LiveChatAEError) {
        lastError = e
        notify { it.onError(e) }
        LiveChatAE.onError?.invoke(e)
    }

    private inline fun notify(block: (LiveChatAEListener) -> Unit) {
        for (l in listeners) block(l)
    }

    // ---- Links ----------------------------------------------------------------------------

    private fun open(uri: Uri) {
        val ctx: Context = chatActivity?.get() ?: resumedActivity?.get() ?: app ?: return
        val web = uri.scheme == "http" || uri.scheme == "https"
        val handling = LiveChatAE.linkHandling
        if (web && handling is LiveChatAELinkHandling.Custom) {
            handling.handler(uri)
            return
        }
        val intent = Intent(Intent.ACTION_VIEW, uri)
        if (web && handling == LiveChatAELinkHandling.InApp) {
            // a Custom Tab without the androidx.browser library: the session extra is all it takes
            intent.putExtras(Bundle().apply { putBinder(CUSTOM_TABS_SESSION, null) })
            intent.putExtra(CUSTOM_TABS_TITLE, 1)
        }
        if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
    }

    private fun isChatPage(uri: Uri): Boolean {
        val page = Uri.parse(configuration?.chatUrl ?: return false)
        return uri.scheme == page.scheme && uri.host == page.host && uri.port == page.port && uri.path == page.path
    }

    private fun destroyWebView() {
        val w = webView ?: return
        webView = null
        documentStart = null
        pageReady = false
        (w.parent as? ViewGroup)?.removeView(w)
        w.destroy()
    }

    // ---- WebView clients ------------------------------------------------------------------

    private object Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            if (!request.isForMainFrame) return false   // frames in the chat (a video in an article)
            if (uri.scheme == "about" || (isChatPage(uri) && !request.hasGesture())) return false
            open(uri)
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            if (view === webView) pageReady = false
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (view === webView && clearStorage) {   // the document-start script cleared it: done
                clearStorage = false
                refreshDocumentStart()
            }
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            if (view !== webView || !request.isForMainFrame) return
            val status = response.statusCode
            if (status >= 400) fail(if (status == 404) LiveChatAEError.InvalidKey else LiveChatAEError.Http(status))
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (view !== webView || !request.isForMainFrame) return
            loadFailed = true
            fail(LiveChatAEError.Network(error.description?.toString() ?: "error ${error.errorCode}"))
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            if (view !== webView) {
                view.destroy()
                return true
            }
            // Android killed the web process to free memory: start again
            destroyWebView()
            if (configuration != null) load()
            return true
        }
    }

    private object Chrome : WebChromeClient() {
        // target="_blank" links and window.open (attachments, images, article links):
        // a throw-away web view catches the address, which then opens like any other link
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            val ctx = app ?: return false
            val catcher = WebView(ctx)
            catcher.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                    open(request.url)
                    main.post { v.destroy() }
                    return true
                }
            }
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            transport.webView = catcher
            resultMsg.sendToTarget()
            return true
        }

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            params: FileChooserParams,
        ): Boolean {
            val activity = chatActivity?.get() ?: return false
            fileChooser?.onReceiveValue(null)
            fileChooser = filePathCallback
            return try {
                val intent = params.createIntent()
                if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                activity.startActivityForResult(intent, LiveChatAEActivity.PICK_FILE)
                true
            } catch (_: ActivityNotFoundException) {
                fileChooser = null
                false
            }
        }

        override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            val activity = chatActivity?.get() ?: resumedActivity?.get()
            if (activity == null || activity.isFinishing) {
                result.cancel()
                return true
            }
            AlertDialog.Builder(activity)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                .setOnCancelListener { result.cancel() }
                .show()
            return true
        }

        override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            val activity = chatActivity?.get() ?: resumedActivity?.get()
            if (activity == null || activity.isFinishing) {
                result.cancel()
                return true
            }
            AlertDialog.Builder(activity)
                .setMessage(message)
                .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
                .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                .setOnCancelListener { result.cancel() }
                .show()
            return true
        }
    }

    private fun Uri.origin(): String = scheme + "://" + host + (if (port != -1) ":$port" else "")

    private const val BRIDGE = "LiveChatAEAndroid"
    private const val CUSTOM_TABS_SESSION = "android.support.customtabs.extra.SESSION"
    private const val CUSTOM_TABS_TITLE = "android.support.customtabs.extra.TITLE_VISIBILITY"

    /** For [LiveChatAEActivity]: the files picked for an `<input type="file">`. */
    fun deliverFiles(resultCode: Int, data: Intent?) {
        val callback = fileChooser ?: return
        fileChooser = null
        if (resultCode != Activity.RESULT_OK || data == null) {
            callback.onReceiveValue(null)
            return
        }
        val clip = data.clipData
        val uris = if (clip != null && clip.itemCount > 0) {
            Array(clip.itemCount) { clip.getItemAt(it).uri }
        } else {
            WebChromeClient.FileChooserParams.parseResult(resultCode, data)
        }
        callback.onReceiveValue(uris)
    }
}
