package com.qwe7002.telegram_sms.static_class

import java.nio.charset.Charset

/**
 * Minimal decoder for MMS PDUs (OMA MMS Encapsulation / WAP WSP binary encoding).
 *
 * Two PDU kinds matter here:
 *  - M-Notification.ind (message type 130) is what arrives in the WAP push broadcast. It only
 *    carries metadata plus the content location the real message must be downloaded from.
 *  - M-Retrieve.conf (message type 132) is the downloaded message. Its body is a WSP multipart
 *    that holds the text and the media attachments.
 *
 * Kept free of Android APIs so it can be unit tested on the JVM.
 */
object MmsPdu {
    const val MESSAGE_TYPE_NOTIFICATION_IND: Int = 0x82
    const val MESSAGE_TYPE_RETRIEVE_CONF: Int = 0x84

    private const val ADDRESS_PRESENT_TOKEN = 0x80
    private const val LENGTH_QUOTE = 0x1F
    private const val QUOTE = 0x7F
    private const val SHORT_LENGTH_MAX = 30

    /** A single entry of the multipart body. */
    data class Part(
        val contentType: String,
        val name: String?,
        val charset: String?,
        val data: ByteArray
    ) {
        /** Decodes the payload as text using the charset advertised by the part. */
        fun asText(): String = try {
            String(data, charset?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8)
        } catch (_: Exception) {
            String(data, Charsets.UTF_8)
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Part
            return contentType == other.contentType &&
                    name == other.name &&
                    charset == other.charset &&
                    data.contentEquals(other.data)
        }

        override fun hashCode(): Int {
            var result = contentType.hashCode()
            result = 31 * result + (name?.hashCode() ?: 0)
            result = 31 * result + (charset?.hashCode() ?: 0)
            result = 31 * result + data.contentHashCode()
            return result
        }
    }

    data class Message(
        var messageType: Int = 0,
        var transactionId: String = "",
        var from: String = "",
        var subject: String = "",
        var contentLocation: String = "",
        var messageSize: Long = 0,
        var date: Long = 0,
        var contentType: String = "",
        var parts: List<Part> = emptyList()
    )

    /**
     * Parses an MMS PDU. Malformed input never throws: whatever was decoded before the problem is
     * returned, so a partially readable notification still produces a usable sender / subject.
     */
    @JvmStatic
    fun parse(pdu: ByteArray): Message {
        val message = Message()
        val reader = Reader(pdu)

        try {
            while (reader.hasMore()) {
                val field = reader.readByte()
                // Headers are well-known field names with the high bit set. Anything else means we
                // lost track of the structure, so stop instead of emitting garbage.
                if (field < 0x80) break

                when (field and 0x7F) {
                    0x0C -> message.messageType = reader.readByte() // X-Mms-Message-Type
                    0x18 -> message.transactionId = reader.readTextString() // X-Mms-Transaction-Id
                    0x0D -> reader.readByte() // X-Mms-MMS-Version
                    0x05 -> message.date = reader.readLongInteger() // Date
                    0x09 -> message.from = reader.readFromValue() // From
                    0x16 -> message.subject = reader.readEncodedStringValue() // Subject
                    0x03 -> message.contentLocation = reader.readTextString() // X-Mms-Content-Location
                    0x0E -> message.messageSize = reader.readLongInteger() // X-Mms-Message-Size
                    0x04 -> { // Content-Type: always the last header, the body follows it
                        val contentType = reader.readContentType()
                        message.contentType = contentType.type
                        message.parts = reader.readBody(contentType)
                        return message
                    }

                    else -> reader.skipValue()
                }
            }
        } catch (_: Exception) {
            // Fall through with whatever has been decoded so far.
        }

        return message
    }

    /**
     * WSP well-known content types. Only the codes that show up in MMS are mapped; everything else
     * travels as an extension-media text string anyway.
     */
    private val WELL_KNOWN_CONTENT_TYPES = mapOf(
        0x00 to "*/*", 0x01 to "text/*", 0x02 to "text/html", 0x03 to "text/plain",
        0x06 to "text/x-vCalendar", 0x07 to "text/x-vCard", 0x08 to "text/vnd.wap.wml",
        0x0B to "multipart/*", 0x0C to "multipart/mixed", 0x0F to "multipart/alternative",
        0x10 to "application/*", 0x1C to "image/*", 0x1D to "image/gif", 0x1E to "image/jpeg",
        0x1F to "image/tiff", 0x20 to "image/png", 0x21 to "image/vnd.wap.wbmp",
        0x22 to "application/vnd.wap.multipart.*", 0x23 to "application/vnd.wap.multipart.mixed",
        0x24 to "application/vnd.wap.multipart.form-data",
        0x25 to "application/vnd.wap.multipart.byteranges",
        0x26 to "application/vnd.wap.multipart.alternative", 0x27 to "application/xml",
        0x28 to "text/xml", 0x33 to "application/vnd.wap.multipart.related",
        0x3B to "application/xhtml+xml", 0x3D to "text/css",
        0x3E to "application/vnd.wap.mms-message"
    )

    /** IANA MIBenum -> charset name, for the charsets an MMS text part realistically uses. */
    private val CHARSETS = mapOf(
        3 to "US-ASCII", 4 to "ISO-8859-1", 17 to "Shift_JIS", 18 to "EUC-JP",
        38 to "EUC-KR", 106 to "UTF-8", 113 to "GBK", 1000 to "UTF-16", 1015 to "UTF-16",
        2025 to "GB2312", 2026 to "Big5", 2252 to "windows-1252"
    )

    private data class ContentType(
        val type: String,
        val name: String?,
        val charset: String?
    )

    private class Reader(val data: ByteArray) {
        var pos: Int = 0

        fun hasMore(): Boolean = pos < data.size

        fun peek(): Int = data[pos].toInt() and 0xFF

        fun readByte(): Int = data[pos++].toInt() and 0xFF

        /** Variable length unsigned integer, 7 bits per octet, high bit marks continuation. */
        fun readUintvar(): Long {
            var value = 0L
            var guard = 0
            while (hasMore() && guard++ < 5) {
                val octet = readByte()
                value = (value shl 7) or (octet and 0x7F).toLong()
                if (octet and 0x80 == 0) break
            }
            return value
        }

        fun readTextString(): String {
            if (hasMore() && peek() == QUOTE) pos++
            val start = pos
            while (hasMore() && data[pos].toInt() != 0) pos++
            val text = String(data, start, pos - start, Charsets.ISO_8859_1)
            if (hasMore()) pos++ // trailing NUL
            return text
        }

        /** Short-length (0..30) or the quote octet followed by a uintvar length. */
        fun readValueLength(): Long {
            if (!hasMore()) return 0
            val first = readByte()
            return when {
                first <= SHORT_LENGTH_MAX -> first.toLong()
                first == LENGTH_QUOTE -> readUintvar()
                else -> {
                    pos--
                    0
                }
            }
        }

        fun readLongInteger(): Long {
            if (!hasMore()) return 0
            val first = peek()
            if (first >= 0x80) return (readByte() and 0x7F).toLong() // short integer
            val length = readByte()
            var value = 0L
            repeat(length.coerceAtMost(8)) {
                if (hasMore()) value = (value shl 8) or readByte().toLong()
            }
            return value
        }

        fun readIntegerValue(): Long = readLongInteger()

        /** Encoded-string-value = Text-string | Value-length Char-set Text-string. */
        fun readEncodedStringValue(): String {
            if (!hasMore()) return ""
            if (peek() > LENGTH_QUOTE) return readTextString()

            val length = readValueLength()
            val end = (pos + length).coerceAtMost(data.size.toLong()).toInt()
            val charset = charsetFor(readIntegerValue())
            val start = pos
            while (pos < end && data[pos].toInt() != 0) pos++
            val text = decode(start, pos, charset)
            pos = end
            return text
        }

        /** From = Value-length (Address-present-token Encoded-string-value | Insert-address-token). */
        fun readFromValue(): String {
            val length = readValueLength()
            val end = (pos + length).coerceAtMost(data.size.toLong()).toInt()
            var address = ""
            if (hasMore() && readByte() == ADDRESS_PRESENT_TOKEN) {
                address = readEncodedStringValue()
            }
            pos = end.coerceAtLeast(pos)
            return address
        }

        /** Content-type = Constrained-media | Value-length Media-type *Parameter. */
        fun readContentType(): ContentType {
            if (!hasMore()) return ContentType("", null, null)

            val first = peek()
            if (first > LENGTH_QUOTE) {
                // Constrained-media: well-known short integer or an extension-media text string.
                return if (first >= 0x80) {
                    ContentType(wellKnownContentType(readByte() and 0x7F), null, null)
                } else {
                    ContentType(readTextString(), null, null)
                }
            }

            val length = readValueLength()
            val end = (pos + length).coerceAtMost(data.size.toLong()).toInt()
            val type = if (hasMore() && peek() >= 0x80) {
                wellKnownContentType(readByte() and 0x7F)
            } else {
                readTextString()
            }

            var name: String? = null
            var charset: String? = null
            while (pos < end) {
                val parameter = readByte()
                when {
                    parameter < 0x80 -> { // untyped parameter: token text followed by its value
                        pos--
                        readTextString()
                        if (pos < end) skipValue()
                    }

                    parameter and 0x7F == 0x01 -> charset = charsetFor(readIntegerValue()) // Charset
                    parameter and 0x7F == 0x05 || parameter and 0x7F == 0x17 -> // Name
                        name = readEncodedStringValue().ifEmpty { name }

                    parameter and 0x7F == 0x06 || parameter and 0x7F == 0x18 -> // Filename
                        name = readEncodedStringValue().ifEmpty { name }

                    else -> skipValue()
                }
            }
            pos = end.coerceAtLeast(pos)
            return ContentType(type, name, charset)
        }

        /** Reads the message body once the Content-Type header has been consumed. */
        fun readBody(contentType: ContentType): List<Part> {
            if (!hasMore()) return emptyList()
            if (!contentType.type.contains("multipart")) {
                val data = data.copyOfRange(pos, data.size)
                pos = this.data.size
                return listOf(Part(contentType.type, contentType.name, contentType.charset, data))
            }
            return readMultipart()
        }

        private fun readMultipart(): List<Part> {
            val parts = mutableListOf<Part>()
            val entries = readUintvar().toInt()
            if (entries <= 0) return parts

            for (index in 0 until entries) {
                if (!hasMore()) break
                val headersLength = readUintvar().toInt()
                val dataLength = readUintvar().toInt()
                if (headersLength < 0 || dataLength < 0) break

                val headersStart = pos
                val headersEnd = (headersStart + headersLength).coerceAtMost(data.size)
                val partContentType = readContentType()
                val name = partContentType.name ?: readPartHeadersName(headersEnd)
                pos = headersEnd

                val dataEnd = (pos + dataLength).coerceAtMost(data.size)
                parts.add(
                    Part(
                        contentType = partContentType.type,
                        name = name,
                        charset = partContentType.charset,
                        data = data.copyOfRange(pos, dataEnd)
                    )
                )
                pos = dataEnd
            }
            return parts
        }

        /** Looks for Content-Location / Content-ID in the part headers to name the attachment. */
        private fun readPartHeadersName(headersEnd: Int): String? {
            var name: String? = null
            while (pos < headersEnd) {
                val field = readByte()
                if (field < 0x80) {
                    pos--
                    readTextString() // untyped header name
                    if (pos < headersEnd) skipValue()
                    continue
                }
                when (field and 0x7F) {
                    0x0E, 0x40 -> { // Content-Location, Content-ID
                        val value = readTextString().trim('<', '>')
                        if (name.isNullOrEmpty()) name = value
                    }

                    else -> skipValue()
                }
            }
            return name
        }

        /** Skips a header value of unknown shape without losing the stream position. */
        fun skipValue() {
            if (!hasMore()) return
            when (val first = peek()) {
                LENGTH_QUOTE -> {
                    pos++
                    val length = readUintvar()
                    pos = (pos + length).coerceAtMost(data.size.toLong()).toInt()
                }

                in 0x00..SHORT_LENGTH_MAX -> {
                    pos++
                    pos = (pos + first).coerceAtMost(data.size)
                }

                in 0x80..0xFF -> pos++ // short integer
                else -> readTextString()
            }
        }

        private fun decode(start: Int, end: Int, charset: String?): String = try {
            String(data, start, end - start, charset?.let { Charset.forName(it) } ?: Charsets.UTF_8)
        } catch (_: Exception) {
            String(data, start, end - start, Charsets.UTF_8)
        }
    }

    private fun wellKnownContentType(code: Int): String =
        WELL_KNOWN_CONTENT_TYPES[code] ?: "application/octet-stream"

    private fun charsetFor(mibEnum: Long): String = CHARSETS[mibEnum.toInt()] ?: "UTF-8"
}
