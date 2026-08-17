package com.qwe7002.telegram_sms

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.qwe7002.telegram_sms.data_structure.telegram.RequestMessage
import com.qwe7002.telegram_sms.static_class.MmsPdu
import com.qwe7002.telegram_sms.static_class.Other
import com.qwe7002.telegram_sms.static_class.Phone
import com.qwe7002.telegram_sms.static_class.TelegramApi
import com.qwe7002.telegram_sms.static_class.Template
import com.qwe7002.telegram_sms.value.Const
import com.tencent.mmkv.MMKV
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class WAPReceiver : BroadcastReceiver() {
    companion object {
        private const val MMS_CONTENT_URI = "content://mms"
        private const val MMS_PART_URI = "content://mms/part"

        // m_type of a fully downloaded message (M-Retrieve.conf). A message that is still only a
        // notification carries 130 and has no parts yet.
        private const val MESSAGE_TYPE_RETRIEVE_CONF = 132

        private const val DOWNLOAD_ACTION = "com.qwe7002.telegram_sms.MMS_DOWNLOADED"

        // Both waits have to stay below the 60s the system allows a background broadcast receiver
        // to run, because the work is kept alive with goAsync().
        private const val DOWNLOAD_TIMEOUT_MS = 45_000L

        // How long to wait for the default messaging app to finish downloading the MMS before
        // giving up and forwarding the notification alone.
        private const val PROVIDER_WAIT_MS = 45_000L
        private const val PROVIDER_POLL_INTERVAL_MS = 3_000L

        // Clock skew tolerance when deciding whether a stored message is "the one that just
        // arrived". The mms date column is in seconds.
        private const val DATE_SLACK_SECONDS = 120L

        private const val WAKELOCK_TIMEOUT_MS = 3 * 60 * 1000L

        private val executor = Executors.newCachedThreadPool()
    }

    override fun onReceive(context: Context, intent: Intent) {
        MMKV.initialize(context)
        val action = intent.action
        Log.d(Const.TAG, "Receive action: $action")

        if (action != "android.provider.Telephony.WAP_PUSH_RECEIVED" &&
            action != "android.provider.Telephony.WAP_PUSH_DELIVER"
        ) {
            return
        }

        val preferences = MMKV.defaultMMKV()
        if (!preferences.getBoolean("initialized", false)) {
            Log.i(Const.TAG, "Uninitialized, MMS receiver is deactivated.")
            return
        }

        val contentType = intent.getStringExtra("contentType") ?: intent.type
        if (contentType != "application/vnd.wap.mms-message") {
            Log.d(Const.TAG, "Not an MMS message, content type: $contentType")
            return
        }

        val isDefaultSmsApp = Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
        if (action == "android.provider.Telephony.WAP_PUSH_RECEIVED" && isDefaultSmsApp) {
            // The default SMS app receives both broadcasts for the same message.
            Log.i(Const.TAG, "reject: android.provider.Telephony.WAP_PUSH_RECEIVED.")
            return
        }

        Log.i(Const.TAG, "MMS received, processing...")

        val extras = intent.extras ?: return
        val pdu = intent.getByteArrayExtra("data")
        if (pdu == null) {
            Log.e(Const.TAG, "MMS PDU data is null")
            return
        }

        // Get slot information
        var intentSlot = extras.getInt("slot", -1)
        val subId = extras.getInt("subscription", -1)
        if (Other.getActiveCard(context) >= 2 && intentSlot == -1) {
            @Suppress("DEPRECATION") val manager = SubscriptionManager.from(context)
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_PHONE_STATE
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                try {
                    val info = manager.getActiveSubscriptionInfo(subId)
                    if (info != null) {
                        intentSlot = info.simSlotIndex
                    }
                } catch (e: Exception) {
                    Log.e(Const.TAG, "Failed to get subscription info: ${e.message}", e)
                }
            }
        }
        val slot = intentSlot
        val dualSim = if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_PHONE_STATE
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            Phone.getSimDisplayName(context, slot)
        } else {
            "Unknown"
        }

        // The WAP push only carries the notification (M-Notification.ind): sender, subject, size
        // and the location the actual message has to be downloaded from.
        val notification = MmsPdu.parse(pdu)
        Log.d(
            Const.TAG,
            "MMS notification: transactionId=${notification.transactionId}, " +
                    "location=${notification.contentLocation}"
        )

        val receiveTime = System.currentTimeMillis()
        val applicationContext = context.applicationContext
        val pendingResult = goAsync()
        executor.execute {
            val powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "${Const.TAG}:WAPReceiver"
            )
            wakeLock.acquire(WAKELOCK_TIMEOUT_MS)
            try {
                processMMS(
                    applicationContext,
                    notification,
                    dualSim,
                    subId,
                    isDefaultSmsApp,
                    receiveTime
                )
            } catch (e: Exception) {
                Log.e(Const.TAG, "Failed to process MMS: ${e.message}", e)
            } finally {
                if (wakeLock.isHeld) {
                    wakeLock.release()
                }
                pendingResult.finish()
            }
        }
    }

    /**
     * Resolves the MMS content and forwards it.
     *
     * Where the content comes from depends on who owns the message:
     *  - default SMS app: nobody else downloads it, so download it here.
     *  - otherwise: the default messaging app downloads it and writes it to the provider, so wait
     *    for the matching row to show up.
     */
    private fun processMMS(
        context: Context,
        notification: MmsPdu.Message,
        dualSim: String,
        subId: Int,
        isDefaultSmsApp: Boolean,
        receiveTime: Long
    ) {
        val content = if (isDefaultSmsApp) {
            downloadMms(context, notification, subId)
        } else {
            waitForProviderMessage(context, notification, receiveTime)
        }

        if (content == null) {
            Log.w(Const.TAG, "Unable to retrieve the MMS content, forwarding the notification only.")
        }

        val from = content?.from?.takeIf { it.isNotEmpty() }
            ?: cleanPhoneNumber(notification.from).takeIf { it.isNotEmpty() }
            ?: "Unknown"
        val subject = content?.subject?.takeIf { it.isNotEmpty() }
            ?: notification.subject.takeIf { it.isNotEmpty() }
            ?: "(No Subject)"
        val text = content?.textContent?.takeIf { it.isNotEmpty() }
            ?: if (content == null) "(Unable to retrieve MMS content)" else "(No text content)"

        val values = mapOf(
            "SIM" to dualSim,
            "From" to from,
            "Subject" to subject,
            "Content" to text,
            "ContentType" to notification.contentType.ifEmpty { "application/vnd.wap.multipart.mixed" },
            "Size" to formatFileSize(notification.messageSize)
        )

        val messageText = Template.render(context, "TPL_received_mms", values)

        val images = content?.images.orEmpty()
        val audios = content?.audios.orEmpty()
        val videos = content?.videos.orEmpty()

        if (images.isEmpty() && audios.isEmpty() && videos.isEmpty()) {
            sendTextMessage(context, messageText, subId)
            return
        }

        var captionSent = false
        if (images.isNotEmpty()) {
            sendMediaList(context, "photo", messageText, images, subId)
            captionSent = true
        }
        if (audios.isNotEmpty()) {
            sendMediaList(context, "audio", if (captionSent) "" else messageText, audios, subId)
            captionSent = true
        }
        if (videos.isNotEmpty()) {
            sendMediaList(context, "video", if (captionSent) "" else messageText, videos, subId)
        }
    }

    /**
     * Downloads the message from the MMSC. Only the default SMS app is allowed to do this, and it
     * is the only way to get the content in that mode: the platform stores nothing on our behalf.
     */
    private fun downloadMms(
        context: Context,
        notification: MmsPdu.Message,
        subId: Int
    ): MmsContent? {
        val locationUrl = notification.contentLocation
        if (locationUrl.isEmpty()) {
            Log.e(Const.TAG, "MMS notification has no content location.")
            return null
        }

        val directory = File(context.cacheDir, "mms")
        if (!directory.exists() && !directory.mkdirs()) {
            Log.e(Const.TAG, "Unable to create the MMS cache directory.")
            return null
        }
        val pduFile = File(directory, "download_${System.currentTimeMillis()}.pdu")
        var contentUri: Uri? = null
        var receiver: BroadcastReceiver? = null

        try {
            contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.mms.fileprovider",
                pduFile
            )

            val latch = CountDownLatch(1)
            val downloadResult = AtomicInteger(Activity.RESULT_CANCELED)
            val action = "$DOWNLOAD_ACTION.${pduFile.name}"
            val downloadReceiver = object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context, receiverIntent: Intent) {
                    downloadResult.set(resultCode)
                    latch.countDown()
                }
            }
            receiver = downloadReceiver
            ContextCompat.registerReceiver(
                context,
                downloadReceiver,
                IntentFilter(action),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )

            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                flags = flags or PendingIntent.FLAG_MUTABLE
            }
            val downloadedIntent = PendingIntent.getBroadcast(
                context,
                0,
                Intent(action).setPackage(context.packageName),
                flags
            )

            // The download runs inside the phone process, which needs write access to our file.
            context.grantUriPermission(
                "com.android.phone",
                contentUri,
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION
            )

            getSmsManager(context, subId).downloadMultimediaMessage(
                context,
                locationUrl,
                contentUri,
                null,
                downloadedIntent
            )

            if (!latch.await(DOWNLOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.e(Const.TAG, "Timed out while downloading the MMS.")
                return null
            }
            if (downloadResult.get() != Activity.RESULT_OK) {
                Log.e(Const.TAG, "MMS download failed, result code: ${downloadResult.get()}")
                return null
            }
            if (!pduFile.exists() || pduFile.length() == 0L) {
                Log.e(Const.TAG, "The downloaded MMS is empty.")
                return null
            }

            val retrieveConf = MmsPdu.parse(pduFile.readBytes())
            Log.d(Const.TAG, "Downloaded MMS with ${retrieveConf.parts.size} part(s).")
            return toContent(retrieveConf)
        } catch (e: Exception) {
            Log.e(Const.TAG, "Error downloading the MMS: ${e.message}", e)
            return null
        } finally {
            receiver?.let {
                try {
                    context.unregisterReceiver(it)
                } catch (e: IllegalArgumentException) {
                    Log.d(Const.TAG, "Download receiver already unregistered: ${e.message}")
                }
            }
            contentUri?.let {
                context.revokeUriPermission(
                    it,
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            pduFile.delete()
        }
    }

    @Suppress("DEPRECATION")
    private fun getSmsManager(context: Context, subId: Int): SmsManager {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(SmsManager::class.java)
            if (manager != null) {
                return if (subId >= 0) manager.createForSubscriptionId(subId) else manager
            }
        }
        return if (subId >= 0) {
            SmsManager.getSmsManagerForSubscriptionId(subId)
        } else {
            SmsManager.getDefault()
        }
    }

    /** Converts a parsed M-Retrieve.conf into the media buckets the forwarder works with. */
    private fun toContent(message: MmsPdu.Message): MmsContent {
        val content = MmsContent()
        content.from = cleanPhoneNumber(message.from)
        content.subject = message.subject

        for (part in message.parts) {
            val contentType = part.contentType.lowercase()
            val fileName = buildFileName(part.name, contentType)
            when {
                contentType.startsWith("text/plain") -> {
                    val text = part.asText()
                    if (text.isNotEmpty()) {
                        content.textContent = text
                    }
                }

                contentType.startsWith("image/") ->
                    content.images.add(MmsMedia(fileName, part.contentType, part.data))

                contentType.startsWith("audio/") ->
                    content.audios.add(MmsMedia(fileName, part.contentType, part.data))

                contentType.startsWith("video/") ->
                    content.videos.add(MmsMedia(fileName, part.contentType, part.data))
            }
        }
        return content
    }

    /**
     * Waits for the default messaging app to store the downloaded message, then reads it back.
     *
     * The message is matched on the transaction id or the content location; a message that is
     * older than this notification is never accepted, so a previously received MMS can no longer
     * be forwarded in place of the new one.
     */
    private fun waitForProviderMessage(
        context: Context,
        notification: MmsPdu.Message,
        receiveTime: Long
    ): MmsContent? {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_SMS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(Const.TAG, "READ_SMS is not granted, unable to read the MMS.")
            return null
        }

        val deadline = SystemClock.elapsedRealtime() + PROVIDER_WAIT_MS
        while (true) {
            // Accepting a message that only matches on arrival time is a last resort: keep looking
            // for the transaction id until the wait is nearly over.
            val lastAttempt = SystemClock.elapsedRealtime() + PROVIDER_POLL_INTERVAL_MS >= deadline
            val mmsId = findMmsId(context, notification, receiveTime, lastAttempt)
            if (mmsId != null) {
                Log.d(Const.TAG, "Found MMS ID: $mmsId")
                val content = readMmsFromProvider(context, mmsId)
                if (content.hasContent()) {
                    return content
                }
                Log.d(Const.TAG, "MMS $mmsId has no readable part yet, waiting.")
            }
            if (SystemClock.elapsedRealtime() >= deadline) {
                Log.w(Const.TAG, "The MMS was not stored by the messaging app in time.")
                return null
            }
            try {
                Thread.sleep(PROVIDER_POLL_INTERVAL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
    }

    /**
     * Finds the downloaded message. Only messages received after this notification are considered,
     * preferring an exact transaction id / content location match.
     */
    private fun findMmsId(
        context: Context,
        notification: MmsPdu.Message,
        receiveTime: Long,
        allowTimeOnlyMatch: Boolean
    ): String? {
        var cursor: Cursor? = null
        try {
            // The date column is in seconds.
            val minDate = receiveTime / 1000 - DATE_SLACK_SECONDS
            cursor = context.contentResolver.query(
                MMS_CONTENT_URI.toUri(),
                arrayOf("_id", "tr_id", "ct_l", "date", "m_type"),
                "m_type=? AND date>=?",
                arrayOf(MESSAGE_TYPE_RETRIEVE_CONF.toString(), minDate.toString()),
                "date DESC"
            ) ?: return null

            var newest: String? = null
            while (cursor.moveToNext()) {
                val id = cursor.getString(cursor.getColumnIndexOrThrow("_id")) ?: continue
                val transactionId = cursor.getString(cursor.getColumnIndexOrThrow("tr_id")).orEmpty()
                val location = cursor.getString(cursor.getColumnIndexOrThrow("ct_l")).orEmpty()

                if (notification.transactionId.isNotEmpty() &&
                    transactionId == notification.transactionId
                ) {
                    return id
                }
                if (notification.contentLocation.isNotEmpty() &&
                    location == notification.contentLocation
                ) {
                    return id
                }
                if (newest == null) {
                    newest = id
                }
            }
            // No identifier matched. Everything in this result set still arrived after the
            // notification, so the newest row is the best guess left once waiting is over.
            if (allowTimeOnlyMatch && newest != null) {
                Log.d(Const.TAG, "No transaction id matched, falling back to the newest message.")
                return newest
            }
            return null
        } catch (e: Exception) {
            Log.e(Const.TAG, "Error finding the MMS ID: ${e.message}", e)
            return null
        } finally {
            cursor?.close()
        }
    }

    private fun readMmsFromProvider(context: Context, mmsId: String): MmsContent {
        val content = MmsContent()
        try {
            content.from = getMmsAddress(context, mmsId)
            getMmsParts(context, mmsId, content)
        } catch (e: Exception) {
            Log.e(Const.TAG, "Error reading the MMS from the content provider: ${e.message}", e)
        }
        return content
    }

    /**
     * Get MMS sender address
     */
    private fun getMmsAddress(context: Context, mmsId: String): String {
        var address = ""
        var cursor: Cursor? = null

        try {
            val uri = "$MMS_CONTENT_URI/$mmsId/addr".toUri()
            cursor = context.contentResolver.query(
                uri,
                null,
                "type=137", // 137 = from address
                null,
                null
            )

            cursor?.let {
                if (it.moveToFirst()) {
                    address = it.getString(it.getColumnIndexOrThrow("address")) ?: ""
                }
            }
        } catch (e: Exception) {
            Log.e(Const.TAG, "Error getting MMS address: ${e.message}", e)
        } finally {
            cursor?.close()
        }

        return cleanPhoneNumber(address)
    }

    /**
     * Get MMS parts (text, images, audio, and video)
     */
    private fun getMmsParts(context: Context, mmsId: String, content: MmsContent) {
        var cursor: Cursor? = null

        try {
            val uri = "$MMS_CONTENT_URI/$mmsId/part".toUri()
            cursor = context.contentResolver.query(
                uri,
                null,
                null,
                null,
                null
            )

            cursor?.let {
                while (it.moveToNext()) {
                    val partId = it.getString(it.getColumnIndexOrThrow("_id"))
                    val contentType = optString(it, "ct").orEmpty().lowercase()
                    val text = optString(it, "text")
                    // Providers disagree on which column holds the attachment name.
                    val name = optString(it, "name") ?: optString(it, "fn") ?: optString(it, "cl")
                    val fileName = buildFileName(name, contentType)

                    when {
                        contentType.startsWith("text/plain") -> {
                            val partText = if (!text.isNullOrEmpty()) {
                                text
                            } else {
                                readTextFromPart(context, partId)
                            }
                            if (partText.isNotEmpty()) {
                                content.textContent = partText
                            }
                        }

                        contentType.startsWith("image/") -> {
                            readMediaFromPart(context, partId)?.let { data ->
                                content.images.add(MmsMedia(fileName, contentType, data))
                                Log.d(Const.TAG, "Found image: $fileName, size: ${data.size}")
                            }
                        }

                        contentType.startsWith("audio/") -> {
                            readMediaFromPart(context, partId)?.let { data ->
                                content.audios.add(MmsMedia(fileName, contentType, data))
                                Log.d(Const.TAG, "Found audio: $fileName, size: ${data.size}")
                            }
                        }

                        contentType.startsWith("video/") -> {
                            readMediaFromPart(context, partId)?.let { data ->
                                content.videos.add(MmsMedia(fileName, contentType, data))
                                Log.d(Const.TAG, "Found video: $fileName, size: ${data.size}")
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(Const.TAG, "Error getting MMS parts: ${e.message}", e)
        } finally {
            cursor?.close()
        }
    }

    /** Reads a column that not every provider implementation exposes. */
    private fun optString(cursor: Cursor, column: String): String? {
        val index = cursor.getColumnIndex(column)
        return if (index >= 0) cursor.getString(index) else null
    }

    /**
     * Read text from MMS part
     */
    private fun readTextFromPart(context: Context, partId: String): String {
        var text = ""
        var inputStream: InputStream? = null

        try {
            val partUri = "$MMS_PART_URI/$partId".toUri()
            inputStream = context.contentResolver.openInputStream(partUri)
            inputStream?.let {
                val buffer = ByteArrayOutputStream()
                val data = ByteArray(1024)
                var count: Int
                while (it.read(data).also { c -> count = c } != -1) {
                    buffer.write(data, 0, count)
                }
                text = buffer.toString("UTF-8")
            }
        } catch (e: Exception) {
            Log.e(Const.TAG, "Error reading text from part: ${e.message}", e)
        } finally {
            inputStream?.close()
        }

        return text
    }

    /**
     * Read media data (image/audio/video) from MMS part
     */
    private fun readMediaFromPart(context: Context, partId: String): ByteArray? {
        var inputStream: InputStream? = null

        try {
            val partUri = "$MMS_PART_URI/$partId".toUri()
            inputStream = context.contentResolver.openInputStream(partUri)
            inputStream?.let {
                val buffer = ByteArrayOutputStream()
                val data = ByteArray(4096)
                var count: Int
                while (it.read(data).also { c -> count = c } != -1) {
                    buffer.write(data, 0, count)
                }
                return buffer.toByteArray()
            }
        } catch (e: Exception) {
            Log.e(Const.TAG, "Error reading image from part: ${e.message}", e)
        } finally {
            inputStream?.close()
        }

        return null
    }

    /** Builds a file name Telegram accepts, adding the extension the MIME type implies. */
    private fun buildFileName(name: String?, contentType: String): String {
        val fallback = when {
            contentType.startsWith("image/") -> "image"
            contentType.startsWith("audio/") -> "audio"
            contentType.startsWith("video/") -> "video"
            else -> "media"
        }
        val baseName = name?.takeIf { it.isNotBlank() } ?: fallback
        if (baseName.contains(".")) {
            return baseName
        }
        return "$baseName.${getExtensionFromMimeType(contentType)}"
    }

    /**
     * Get file extension from MIME type
     */
    private fun getExtensionFromMimeType(mimeType: String): String {
        return when (mimeType.lowercase()) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/bmp" -> "bmp"
            "image/webp" -> "webp"
            "audio/mpeg", "audio/mp3" -> "mp3"
            "audio/aac" -> "aac"
            "audio/amr" -> "amr"
            "audio/amr-wb" -> "awb"
            "audio/ogg" -> "ogg"
            "audio/wav", "audio/x-wav" -> "wav"
            "audio/3gpp" -> "3gp"
            "audio/mp4" -> "m4a"
            "audio/x-ms-wma" -> "wma"
            "video/mp4", "video/h264" -> "mp4"
            "video/3gpp", "video/3gpp2" -> "3gp"
            "video/mpeg" -> "mpeg"
            "video/webm" -> "webm"
            "video/x-msvideo" -> "avi"
            "video/quicktime" -> "mov"
            else -> mimeType.substringAfterLast('/', "bin").substringBefore('+')
        }
    }

    /**
     * Send media to Telegram, the first item carries the caption
     */
    private fun sendMediaList(
        context: Context,
        mediaType: String,
        caption: String,
        mediaList: List<MmsMedia>,
        subId: Int
    ) {
        mediaList.forEachIndexed { index, media ->
            val mediaCaption = if (index == 0) caption else ""
            TelegramApi.sendMedia(
                context = context,
                mediaType = mediaType,
                media = TelegramApi.MediaData(media.fileName, media.contentType, media.data),
                caption = mediaCaption,
                fallbackSubId = if (mediaCaption.isNotEmpty()) subId else -1
            ) {
                Log.i(Const.TAG, "MMS $mediaType sent successfully")
            }
        }
    }

    /**
     * Send text message to Telegram
     */
    private fun sendTextMessage(
        context: Context,
        text: String,
        subId: Int
    ) {
        val requestBody = RequestMessage()
        requestBody.text = text

        TelegramApi.sendMessage(
            context = context,
            requestBody = requestBody,
            fallbackSubId = subId
        ) {
            Log.i(Const.TAG, "MMS text message forwarded successfully")
        }
    }

    /**
     * Format file size to human readable format
     */
    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes <= 0 -> "Unknown"
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "${bytes / (1024 * 1024)} MB"
        }
    }

    /**
     * Clean phone number from address string
     */
    private fun cleanPhoneNumber(address: String): String {
        // Remove /TYPE=PLMN suffix and other type suffixes
        var result = address
            .replace("/TYPE=PLMN", "")
            .replace("/TYPE=IPv4", "")
            .replace("/TYPE=IPv6", "")
            .trim()

        // Extract phone number or email from the string
        val phonePattern = Regex("[+]?[0-9]{6,15}")
        val emailPattern = Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")

        val phoneMatch = phonePattern.find(result)
        val emailMatch = emailPattern.find(result)

        result = when {
            phoneMatch != null -> phoneMatch.value
            emailMatch != null -> emailMatch.value
            else -> result
        }

        return result
    }

    /**
     * Data class to hold the resolved MMS content
     */
    private data class MmsContent(
        var from: String = "",
        var subject: String = "",
        var textContent: String = "",
        val images: MutableList<MmsMedia> = mutableListOf(),
        val audios: MutableList<MmsMedia> = mutableListOf(),
        val videos: MutableList<MmsMedia> = mutableListOf()
    ) {
        fun hasContent(): Boolean =
            textContent.isNotEmpty() || images.isNotEmpty() || audios.isNotEmpty() || videos.isNotEmpty()
    }

    /**
     * Data class to hold MMS media (image/audio/video)
     */
    private data class MmsMedia(
        val fileName: String,
        val contentType: String,
        val data: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as MmsMedia
            if (fileName != other.fileName) return false
            if (contentType != other.contentType) return false
            if (!data.contentEquals(other.data)) return false
            return true
        }

        override fun hashCode(): Int {
            var result = fileName.hashCode()
            result = 31 * result + contentType.hashCode()
            result = 31 * result + data.contentHashCode()
            return result
        }
    }
}
