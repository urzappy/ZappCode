package com.zapp.net.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Their protocol against mine, byte for byte.
 *
 * Every expected value below was produced by running **their own minified functions** in Node
 * (`C:\Users\hachkerss\AppData\Local\Temp\opencode\theirs-pty.mjs`, lifted from
 * `solid-B85sHMHe.js`), not written from reading the code. If a value here disagrees with the
 * comment, the comment wins and this port is wrong.
 */
class PtyProtocolTest {

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    // ---------------------------------------------------------------- F(e, t)

    @Test
    fun `input frame with no payload is the 5 byte resize header`() {
        // {"cols":80,"rows":24} payload=null -> 0000500018
        assertEquals("0000500018", hex(PtyProtocol.inputFrame(80, 24, null)))
        // {"cols":120,"rows":40} payload= -> 0100780028   (a zero-length payload still sets byte 0)
        assertEquals("0100780028", hex(PtyProtocol.inputFrame(120, 40, ByteArray(0))))
    }

    @Test
    fun `input frame writes cols and rows big endian`() {
        // {"cols":1,"rows":1} payload=61 -> 010001000161
        assertEquals("010001000161", hex(PtyProtocol.inputFrame(1, 1, byteArrayOf(0x61))))
        // {"cols":65535,"rows":65535} payload=ff007f -> 01ffffffffff007f
        assertEquals("01ffffffffff007f", hex(PtyProtocol.inputFrame(65535, 65535, byteArrayOf(-1, 0, 0x7f))))
        // {"cols":0,"rows":0} payload=010203040506 -> 0100000000010203040506
        assertEquals("0100000000010203040506", hex(PtyProtocol.inputFrame(0, 0, byteArrayOf(1, 2, 3, 4, 5, 6))))
    }

    @Test
    fun `input frame carries the keystrokes after the header`() {
        // {"cols":100,"rows":30} payload=6c73202d6c610d -> 010064001e6c73202d6c610d  ("ls -la\r")
        assertEquals(
            "010064001e6c73202d6c610d",
            hex(PtyProtocol.inputFrame(100, 30, "ls -la\r".toByteArray(Charsets.UTF_8))),
        )
    }

    // --------------------------------------------------- encodeURIComponent

    @Test
    fun `encodeComponent matches encodeURIComponent`() {
        // Node: encodeURIComponent("~!*'()") === "~!*'()"
        assertEquals("~!*'()", PtyProtocol.encodeComponent("~!*'()"))
        // Node: encodeURIComponent("p/x y") === "p%2Fx%20y"   (a space is %20, never +)
        assertEquals("p%2Fx%20y", PtyProtocol.encodeComponent("p/x y"))
        // Node: encodeURIComponent("é☃") === "%C3%A9%E2%98%83"
        assertEquals("%C3%A9%E2%98%83", PtyProtocol.encodeComponent("é☃"))
        assertEquals("%2B%25", PtyProtocol.encodeComponent("+%"))
    }

    // ------------------------------------------------ URLSearchParams set()

    @Test
    fun `formEncode matches URLSearchParams serialisation`() {
        // Node: new URLSearchParams({v:"~!*'()"}) === "v=%7E%21*%27%28%29"
        assertEquals("%7E%21*%27%28%29", PtyProtocol.formEncode("~!*'()"))
        // Node: {v:"a b&c=d"} -> "v=a+b%26c%3Dd"   (space is +, unlike encodeURIComponent)
        assertEquals("a+b%26c%3Dd", PtyProtocol.formEncode("a b&c=d"))
        assertEquals("att+2", PtyProtocol.formEncode("att 2"))
        assertEquals("", PtyProtocol.formEncode(""))
    }

    // -------------------------------------------------------------- I(...)

    @Test
    fun `connect url is their url exactly`() {
        // {"base":"http://127.0.0.1:4096","ptyID":"pty_abc","cursor":0,"attachmentID":"att_1",
        //  "takeover":true,"ticket":"T1"}
        // -> ws://127.0.0.1:4096/api/experimental/persistent-pty/pty_abc/connect
        //    ?ticket=T1&cursor=0&attachment_id=att_1&takeover=true&input_protocol=1
        assertEquals(
            "ws://127.0.0.1:4096/api/experimental/persistent-pty/pty_abc/connect" +
                "?ticket=T1&cursor=0&attachment_id=att_1&takeover=true&input_protocol=1",
            PtyProtocol.connectUrl("http://127.0.0.1:4096", "pty_abc", "T1", 0, "att_1", true),
        )
    }

    @Test
    fun `connect url encodes ticket and attachment like searchParams`() {
        // {"base":"http://127.0.0.1:4096/","ptyID":"pty_abc","cursor":1234,
        //  "attachmentID":"att 2","takeover":false,"ticket":"a b&c=d"}
        // -> ...?ticket=a+b%26c%3Dd&cursor=1234&attachment_id=att+2&takeover=false&input_protocol=1
        assertEquals(
            "ws://127.0.0.1:4096/api/experimental/persistent-pty/pty_abc/connect" +
                "?ticket=a+b%26c%3Dd&cursor=1234&attachment_id=att+2&takeover=false&input_protocol=1",
            PtyProtocol.connectUrl("http://127.0.0.1:4096/", "pty_abc", "a b&c=d", 1234, "att 2", false),
        )
    }

    @Test
    fun `connect url upgrades https to wss and escapes the pty id`() {
        // {"base":"https://x.example","ptyID":"p/x y","cursor":99,"attachmentID":"at#3",
        //  "takeover":undefined,"ticket":"t?"}
        // -> wss://x.example/api/experimental/persistent-pty/p%2Fx%20y/connect
        //    ?ticket=t%3F&cursor=99&attachment_id=at%233&takeover=false&input_protocol=1
        assertEquals(
            "wss://x.example/api/experimental/persistent-pty/p%2Fx%20y/connect" +
                "?ticket=t%3F&cursor=99&attachment_id=at%233&takeover=false&input_protocol=1",
            PtyProtocol.connectUrl("https://x.example", "p/x y", "t?", 99, "at#3", false),
        )
    }

    @Test
    fun `connect url keeps a base path, as their URL resolution does`() {
        // {"base":"ws://h:1/base",...} -> ws://h:1/base/api/experimental/...
        // their code appends "/" to the pathname, so the relative resolves *inside* /base.
        assertEquals(
            "ws://h:1/base/api/experimental/persistent-pty/id/connect" +
                "?ticket=k&cursor=5&attachment_id=a&takeover=false&input_protocol=1",
            PtyProtocol.connectUrl("ws://h:1/base", "id", "k", 5, "a", false),
        )
    }

    @Test
    fun `an empty pty id yields the double slash their own output has`() {
        // {"base":"http://192.168.1.7:4096","ptyID":"","cursor":-1,"attachmentID":"",
        //  "takeover":true,"ticket":""} -> ws://192.168.1.7:4096/api/experimental/persistent-pty//connect?...
        assertEquals(
            "ws://192.168.1.7:4096/api/experimental/persistent-pty//connect" +
                "?ticket=&cursor=-1&attachment_id=&takeover=true&input_protocol=1",
            PtyProtocol.connectUrl("http://192.168.1.7:4096", "", "", -1, "", true),
        )
    }

    // --------------------------------------------------------------- decode

    @Test
    fun `decode reads the seven events their schema declares`() {
        val attached = PtyProtocol.decode(
            """{"type":"attached","inputProtocol":1,"info":{"size":{"cols":80,"rows":24}},"role":"controller"}"""
        )
        assertTrue(attached is PtyProtocol.Event.Attached)
        attached as PtyProtocol.Event.Attached
        assertEquals(1, attached.inputProtocol)
        assertEquals(PtyProtocol.Size(80, 24), attached.size)
        assertEquals("controller", attached.role)

        assertEquals(PtyProtocol.Event.ReplayComplete, PtyProtocol.decode("""{"type":"replay_complete"}"""))

        val resized = PtyProtocol.decode("""{"type":"resized","cols":120,"rows":40,"checkpoint":null}""")
        assertEquals(PtyProtocol.Event.Resized(120, 40, null), resized)

        val controller = PtyProtocol.decode("""{"type":"controller_changed","attachmentID":"a1"}""")
        assertEquals(PtyProtocol.Event.ControllerChanged("a1"), controller)

        val exited = PtyProtocol.decode("""{"type":"exited","exitCode":0}""")
        assertEquals(PtyProtocol.Event.Exited(0), exited)

        val title = PtyProtocol.decode("""{"type":"title_changed","title":"zsh"}""")
        assertEquals(PtyProtocol.Event.TitleChanged("zsh"), title)

        val fg = PtyProtocol.decode("""{"type":"foreground_process_changed","process":null}""")
        assertEquals(PtyProtocol.Event.ForegroundProcessChanged(null), fg)
    }

    @Test
    fun `decode keeps an unknown type instead of throwing`() {
        assertEquals(PtyProtocol.Event.Unknown("brand_new"), PtyProtocol.decode("""{"type":"brand_new"}"""))
        assertEquals(PtyProtocol.Event.Unknown(""), PtyProtocol.decode("not json at all"))
        assertNull(
            (PtyProtocol.decode("""{"type":"exited"}""") as PtyProtocol.Event.Exited).exitCode
        )
    }
}
