package com.qwe7002.telegram_sms.static_class

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class MmsPduTest {
    /** Helper for assembling binary WSP encoded PDUs. */
    private class Buf {
        private val out = ByteArrayOutputStream()
        fun b(vararg values: Int) = apply { values.forEach { out.write(it and 0xFF) } }
        fun s(text: String) = apply {
            out.write(text.toByteArray(Charsets.UTF_8))
            out.write(0)
        }

        fun raw(data: ByteArray) = apply { out.write(data) }
        fun bytes(): ByteArray = out.toByteArray()
    }

    private fun notificationInd(): ByteArray {
        // From = Value-length Address-present-token Encoded-string-value
        val from = Buf().b(0x80).s("+886912345678/TYPE=PLMN").bytes()
        // Subject = Value-length Char-set(UTF-8) Text-string
        val subject = Buf().b(0xEA).s("Hello").bytes()
        return Buf()
            .b(0x8C, 0x82)                        // X-Mms-Message-Type: m-notification-ind
            .b(0x98).s("T123")                    // X-Mms-Transaction-Id
            .b(0x8D, 0x90)                        // X-Mms-MMS-Version: 1.0
            .b(0x89, from.size).raw(from)         // From
            .b(0x96, subject.size).raw(subject)   // Subject
            .b(0x8A, 0x80)                        // X-Mms-Message-Class (header we skip)
            .b(0x8E, 0x02, 0x27, 0x10)            // X-Mms-Message-Size: 10000
            .b(0x88, 0x03, 0x81, 0x02, 0x00)      // X-Mms-Expiry (header we skip)
            .b(0x83).s("http://mmsc.example/abc") // X-Mms-Content-Location
            .bytes()
    }

    private fun retrieveConf(): ByteArray {
        // multipart.related; type=application/smil; start=<smil>
        val contentType = Buf().b(0xB3).b(0x89).s("application/smil").b(0x8A).s("<smil>").bytes()

        val textType = Buf().b(0x83, 0x81, 0xEA).bytes() // text/plain; charset=utf-8
        val textHeaders = Buf().b(textType.size).raw(textType).b(0x8E).s("text.txt").bytes()
        val textData = "Hello MMS".toByteArray()

        val imageType = Buf().b(0x9E).b(0x85).s("pic.jpg").bytes() // image/jpeg; name=pic.jpg
        val imageHeaders = Buf().b(imageType.size).raw(imageType).bytes()
        val imageData = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())

        return Buf()
            .b(0x8C, 0x84)                                  // X-Mms-Message-Type: m-retrieve-conf
            .b(0x98).s("T123")
            .b(0x8D, 0x90)
            .b(0x89, 13).b(0x80).s("+8869000000")           // From
            .b(0x96).s("Trip")                              // Subject as a plain text string
            .b(0x84, contentType.size).raw(contentType)     // Content-Type, the last header
            .b(0x02)                                        // multipart entry count
            .b(textHeaders.size, textData.size).raw(textHeaders).raw(textData)
            .b(imageHeaders.size, imageData.size).raw(imageHeaders).raw(imageData)
            .bytes()
    }

    @Test
    fun parsesNotificationInd() {
        val message = MmsPdu.parse(notificationInd())
        assertEquals(MmsPdu.MESSAGE_TYPE_NOTIFICATION_IND, message.messageType)
        assertEquals("T123", message.transactionId)
        assertEquals("+886912345678/TYPE=PLMN", message.from)
        assertEquals("Hello", message.subject)
        assertEquals(10000L, message.messageSize)
        assertEquals("http://mmsc.example/abc", message.contentLocation)
        assertTrue(message.parts.isEmpty())
    }

    @Test
    fun parsesRetrieveConfWithAttachments() {
        val message = MmsPdu.parse(retrieveConf())
        assertEquals(MmsPdu.MESSAGE_TYPE_RETRIEVE_CONF, message.messageType)
        assertEquals("T123", message.transactionId)
        assertEquals("+8869000000", message.from)
        assertEquals("Trip", message.subject)
        assertEquals("application/vnd.wap.multipart.related", message.contentType)
        assertEquals(2, message.parts.size)

        val text = message.parts[0]
        assertEquals("text/plain", text.contentType)
        assertEquals("UTF-8", text.charset)
        assertEquals("Hello MMS", text.asText())

        val image = message.parts[1]
        assertEquals("image/jpeg", image.contentType)
        assertEquals("pic.jpg", image.name)
        assertEquals(4, image.data.size)
        assertEquals(0xFF.toByte(), image.data[0])
    }

    @Test
    fun malformedInputIsTolerated() {
        val truncated = MmsPdu.parse(notificationInd().copyOfRange(0, 12))
        assertEquals("T123", truncated.transactionId)

        assertTrue(MmsPdu.parse(ByteArray(64) { 0x41 }).parts.isEmpty())
        assertEquals(0, MmsPdu.parse(ByteArray(0)).messageType)
    }
}
