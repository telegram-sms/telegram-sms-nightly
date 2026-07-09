package com.qwe7002.telegram_sms.data_structure.telegram

import com.google.gson.annotations.SerializedName

@Suppress("unused")
object ReplyMarkupKeyboard {
    @JvmStatic
    fun getInlineKeyboardObj(
        text: String,
        callbackData: String
    ): ArrayList<InlineKeyboardButton> {
        val button = InlineKeyboardButton()
        button.text = text
        button.callbackData = callbackData
        val buttonArraylist = ArrayList<InlineKeyboardButton>()
        buttonArraylist.add(button)
        return buttonArraylist
    }

    @JvmStatic
    fun createButton(text: String, callbackData: String): InlineKeyboardButton {
        return InlineKeyboardButton().apply {
            this.text = text
            this.callbackData = callbackData
        }
    }

    @JvmStatic
    fun createButtonRow(vararg buttons: InlineKeyboardButton): ArrayList<InlineKeyboardButton> {
        return ArrayList(buttons.toList())
    }

    @JvmStatic
    fun createPaginationKeyboard(
        currentPage: Int,
        totalPages: Int,
        callbackPrefix: String,
        prevText: String = "◀️",
        nextText: String = "▶️"
    ): ArrayList<ArrayList<InlineKeyboardButton>> {
        val keyboard = ArrayList<ArrayList<InlineKeyboardButton>>()
        val navRow = ArrayList<InlineKeyboardButton>()

        if (currentPage > 0) {
            navRow.add(createButton(prevText, "${callbackPrefix}:${currentPage - 1}"))
        }
        navRow.add(createButton("${currentPage + 1}/$totalPages", "${callbackPrefix}:current"))
        if (currentPage < totalPages - 1) {
            navRow.add(createButton(nextText, "${callbackPrefix}:${currentPage + 1}"))
        }

        if (navRow.isNotEmpty()) {
            keyboard.add(navRow)
        }
        return keyboard
    }

    /**
     * Conversation list: one button per conversation (opens the thread) plus a
     * pagination row. [conversations] is a list of (threadId, address, count).
     */
    @JvmStatic
    fun createConversationListKeyboard(
        conversations: List<Triple<Long, String, Int>>,
        currentPage: Int,
        totalPages: Int
    ): ArrayList<ArrayList<InlineKeyboardButton>> {
        val keyboard = ArrayList<ArrayList<InlineKeyboardButton>>()

        for ((threadId, address, count) in conversations) {
            val label = "💬 ${address.ifEmpty { "?" }} ($count)"
            keyboard.add(getInlineKeyboardObj(label, "sms_thread:$threadId:0"))
        }

        val navRow = ArrayList<InlineKeyboardButton>()
        if (currentPage > 0) {
            navRow.add(createButton("◀️", "sms_conv:${currentPage - 1}"))
        }
        navRow.add(createButton("${currentPage + 1}/$totalPages", "sms_conv:current"))
        if (currentPage < totalPages - 1) {
            navRow.add(createButton("▶️", "sms_conv:${currentPage + 1}"))
        }
        if (navRow.isNotEmpty()) {
            keyboard.add(navRow)
        }

        return keyboard
    }

    /**
     * Thread (conversation detail) view: a delete button per message on the
     * current page (grouped three per row), a pagination row, and a button to
     * return to the conversation list.
     */
    @JvmStatic
    fun createThreadKeyboard(
        threadId: Long,
        messageIds: List<Long>,
        currentPage: Int,
        totalPages: Int
    ): ArrayList<ArrayList<InlineKeyboardButton>> {
        val keyboard = ArrayList<ArrayList<InlineKeyboardButton>>()

        var row = ArrayList<InlineKeyboardButton>()
        for (id in messageIds) {
            row.add(createButton("🗑️ #$id", "sms_tdel_confirm:$id:$threadId"))
            if (row.size == 3) {
                keyboard.add(row)
                row = ArrayList()
            }
        }
        if (row.isNotEmpty()) {
            keyboard.add(row)
        }

        val navRow = ArrayList<InlineKeyboardButton>()
        if (currentPage > 0) {
            navRow.add(createButton("◀️", "sms_thread:$threadId:${currentPage - 1}"))
        }
        navRow.add(createButton("${currentPage + 1}/$totalPages", "sms_thread:$threadId:current"))
        if (currentPage < totalPages - 1) {
            navRow.add(createButton("▶️", "sms_thread:$threadId:${currentPage + 1}"))
        }
        if (navRow.isNotEmpty()) {
            keyboard.add(navRow)
        }

        keyboard.add(getInlineKeyboardObj("◀️ Back", "sms_conv:0"))
        return keyboard
    }

    /**
     * Delete confirmation shown from within a thread. Cancelling returns to the
     * thread rather than a flat detail view.
     */
    @JvmStatic
    fun createThreadDeleteConfirmKeyboard(
        smsId: Long,
        threadId: Long
    ): ArrayList<ArrayList<InlineKeyboardButton>> {
        val keyboard = ArrayList<ArrayList<InlineKeyboardButton>>()
        val confirmRow = ArrayList<InlineKeyboardButton>()
        confirmRow.add(createButton("✅ Confirm", "sms_tdel:$smsId:$threadId"))
        confirmRow.add(createButton("❌ Cancel", "sms_thread:$threadId:0"))
        keyboard.add(confirmRow)
        return keyboard
    }

    class KeyboardMarkup {
        @SerializedName("inline_keyboard")
        lateinit var inlineKeyboard: ArrayList<ArrayList<InlineKeyboardButton>>
        var oneTimeKeyboard: Boolean = true
    }

    class InlineKeyboardButton {
        lateinit var text: String

        @SerializedName("callback_data")
        lateinit var callbackData: String
    }

    /**
     * Reply keyboard markup for displaying command buttons
     */
    class ReplyKeyboardMarkup {
        @SerializedName("keyboard")
        lateinit var keyboard: ArrayList<ArrayList<ReplyKeyboardButton>>

        @SerializedName("resize_keyboard")
        var resizeKeyboard: Boolean = true

        @SerializedName("one_time_keyboard")
        var oneTimeKeyboard: Boolean = false

        @SerializedName("is_persistent")
        var isPersistent: Boolean = true
    }

    class ReplyKeyboardButton {
        lateinit var text: String
    }

    /**
     * Create a ReplyKeyboardButton with the given text
     */
    @JvmStatic
    fun createReplyButton(text: String): ReplyKeyboardButton {
        return ReplyKeyboardButton().apply {
            this.text = text
        }
    }

    /**
     * Create a row of reply keyboard buttons
     */
    @JvmStatic
    fun createReplyButtonRow(vararg buttons: ReplyKeyboardButton): ArrayList<ReplyKeyboardButton> {
        return ArrayList(buttons.toList())
    }

    /**
     * Remove the current custom keyboard and display the default letter-keyboard
     */
    class ReplyKeyboardRemove {
        @SerializedName("remove_keyboard")
        var removeKeyboard: Boolean = true

        @SerializedName("selective")
        var selective: Boolean = false
    }
}
