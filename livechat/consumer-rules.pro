# The JavaScript bridge is called by name from the chat page.
-keepclassmembers class ae.livechat.sdk.ChatSession$JsBridge {
    @android.webkit.JavascriptInterface <methods>;
}
