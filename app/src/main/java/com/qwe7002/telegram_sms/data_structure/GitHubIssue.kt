package com.qwe7002.telegram_sms.data_structure

import com.google.gson.annotations.SerializedName

data class GitHubIssueRequest(
    val title: String,
    val body: String,
    val labels: List<String> = listOf("bug", "auto-report")
)

data class GitHubIssueResponse(
    val id: Long,
    val number: Int,
    @SerializedName("html_url")
    val htmlUrl: String,
    val title: String,
    val state: String
)
