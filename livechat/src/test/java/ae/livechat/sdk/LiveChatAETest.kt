package ae.livechat.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveChatAETest {
    @Test
    fun chatUrl() {
        val c = LiveChatAEConfiguration("abc_123", "https://livechat.ae/", "it", "Vendite & Co")
        assertEquals("https://livechat.ae/sdk/chat?key=abc_123&lang=it&department=Vendite%20%26%20Co", c.chatUrl)
        assertEquals("https://livechat.ae", c.origin)
        val local = LiveChatAEConfiguration("demo", "http://10.0.2.2:8080")
        assertEquals("http://10.0.2.2:8080/sdk/chat?key=demo", local.chatUrl)
        assertEquals("http://10.0.2.2:8080", local.origin)
        val path = LiveChatAEConfiguration("k", "https://example.com/chat/")
        assertEquals("https://example.com/chat/sdk/chat?key=k", path.chatUrl)
        assertEquals("https://example.com", path.origin)
        assertNull(LiveChatAEConfiguration("").chatUrl)
        assertNull(LiveChatAEConfiguration("demo", "livechat.ae").chatUrl)
        assertNull(LiveChatAEConfiguration("demo", "javascript:alert(1)").chatUrl)
    }

    @Test
    fun parseMessage() {
        val json = """{"type":"message","id":42,"sender":"AGENT","author":"Sara","text":"Ciao!",
            "createdAt":"2026-11-24T06:30:00.123456Z",
            "attachment":{"name":"fattura.pdf","url":"https://livechat.ae/api/visitor/files/x?chatId=c&token=t","contentType":"application/pdf"}}"""
        val m = (BridgeEvent.parse(json) as BridgeEvent.Message).message
        assertEquals(42L, m.id)
        assertEquals(LiveChatAEMessage.Sender.AGENT, m.sender)
        assertEquals("Sara", m.author)
        assertEquals("Ciao!", m.text)
        assertEquals("fattura.pdf", m.attachment?.name)
        assertEquals("application/pdf", m.attachment?.contentType)
        assertTrue(m.attachment!!.url!!.startsWith("https://livechat.ae/"))
        assertEquals(1_795_501_800_123L, m.date?.time)

        val visitor = (BridgeEvent.parse("""{"type":"message","sender":"VISITOR","text":"hi","createdAt":null}""") as BridgeEvent.Message).message
        assertNull(visitor.id)
        assertNull(visitor.attachment)
        assertNull(visitor.date)
        assertEquals(LiveChatAEMessage.Sender.VISITOR, visitor.sender)
        val odd = (BridgeEvent.parse("""{"type":"message","sender":"BOT"}""") as BridgeEvent.Message).message
        assertEquals(LiveChatAEMessage.Sender.UNKNOWN, odd.sender)
    }

    @Test
    fun parseOtherEvents() {
        assertEquals(BridgeEvent.Unread(3), BridgeEvent.parse("""{"type":"unread","count":3}"""))
        assertEquals(BridgeEvent.Unread(0), BridgeEvent.parse("""{"type":"unread","count":-1}"""))
        assertEquals(BridgeEvent.ChatStarted("c1"), BridgeEvent.parse("""{"type":"chat_started","chatId":"c1"}"""))
        assertEquals(BridgeEvent.ChatClosed("c1"), BridgeEvent.parse("""{"type":"chat_closed","chatId":"c1"}"""))
        assertEquals(BridgeEvent.CloseRequested, BridgeEvent.parse("""{"type":"close_requested"}"""))
        assertEquals(BridgeEvent.Status(true), BridgeEvent.parse("""{"type":"status","online":true}"""))
        assertEquals(BridgeEvent.Ready(false, null, 2), BridgeEvent.parse("""{"type":"ready","online":false,"chatId":null,"unread":2,"title":"Demo"}"""))
        assertEquals(BridgeEvent.Ready(true, "c9", 0), BridgeEvent.parse("""{"type":"ready","online":true,"chatId":"c9"}"""))
        assertEquals(BridgeEvent.Error(LiveChatAEError.TooManyChats), BridgeEvent.parse("""{"type":"error","code":"too_many_chats"}"""))
        assertEquals(BridgeEvent.Error(LiveChatAEError.InvalidKey), BridgeEvent.parse("""{"type":"error","code":"missing_key"}"""))
        assertEquals(BridgeEvent.Error(LiveChatAEError.Other("boh")), BridgeEvent.parse("""{"type":"error","code":"boh"}"""))
        assertNull(BridgeEvent.parse("""{"type":"unknown"}"""))
        assertNull(BridgeEvent.parse("not json"))
        assertNull(BridgeEvent.parse(null))
    }

    @Test
    fun dates() {
        assertEquals(1_795_501_800_000L, BridgeEvent.date("2026-11-24T06:30:00Z")?.time)
        assertEquals(1_795_501_800_000L, BridgeEvent.date("2026-11-24T10:30:00+04:00")?.time)
        assertEquals(1_795_501_800_500L, BridgeEvent.date(1_795_501_800.5)?.time)
        assertNull(BridgeEvent.date("yesterday"))
    }

    @Test
    fun scriptsAreSafeJavaScript() {
        // quotes, backslashes, a closing script tag and a line separator stay inside the string
        val hostile = "Sara \"</script>\\   ' end"
        val js = Script.call("identify", hostile)
        assertTrue(js, js.contains("var a=\"Sara \\\"<\\/script>\\\\ \\u2028 ' end\";"))
        assertFalse(js.contains(" "))
        assertEquals("""{"email":"s@x.it","name":"Sara"}""", Script.json(mapOf("name" to "Sara", "email" to "s@x.it")))
        assertEquals("""window.LiveChatAE=[["setVisitor",{"name":"Sara"}],["identify","tok"]];""",
            Script.queue(listOf("setVisitor" to mapOf("name" to "Sara"), "identify" to "tok")))
        assertTrue(Script.call("open").contains("var a=undefined;"))
        assertEquals("""{"device":"fcm:1","platform":"android"}""", Script.json(mapOf("platform" to "android", "device" to "fcm:1")))
        assertEquals("\"a\\nb\\u0001\"", Script.json("a\nb\u0001"))
    }

    @Test
    fun notifications() {
        assertTrue(LiveChatAE.isLiveChatNotification(mapOf("livechatae" to """{"chatId":"c1"}""")))
        assertFalse(LiveChatAE.isLiveChatNotification(mapOf("other" to "x")))
        assertFalse(LiveChatAE.isLiveChatNotification(null as Map<String, String?>?))
    }
}
