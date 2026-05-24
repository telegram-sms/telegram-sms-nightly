package com.qwe7002.telegram_sms.static_class

import android.os.Build
import android.util.Log
import com.google.gson.Gson
import com.qwe7002.telegram_sms.BuildConfig
import com.qwe7002.telegram_sms.data_structure.GitHubIssueRequest
import com.qwe7002.telegram_sms.data_structure.GitHubIssueResponse
import com.qwe7002.telegram_sms.value.Const
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

object GitHubApi {
    private val gson = Gson()
    private const val GITHUB_REPO_OWNER = "telegram-sms"

    private fun getRepoName(): String {
        var repoName = "telegram-sms"
        if (BuildConfig.VERSION_NAME.contains("nightly")) {
            repoName += "-nightly"
        }
        return repoName
    }

    /**
     * Create a GitHub issue with log content and device info.
     *
     * @param logContent The log text to include in the issue body
     * @param onSuccess Callback with the created issue URL
     * @param onFailure Callback with error message
     */
    fun createIssue(
        logContent: String,
        onSuccess: (String) -> Unit,
        onFailure: (String) -> Unit
    ) {
        val repoName = getRepoName()
        val deviceInfo = buildDeviceInfo()
        val title = "[Auto Report] ${BuildConfig.VERSION_NAME} - ${Build.MODEL}"
        val body = buildIssueBody(deviceInfo, logContent)

        val issueRequest = GitHubIssueRequest(
            title = title,
            body = body
        )

        val requestUri = "https://api.github.com/repos/$GITHUB_REPO_OWNER/$repoName/issues"
        val requestBodyJson = gson.toJson(issueRequest)
        val requestBody = requestBodyJson.toRequestBody(Const.JSON)

        val okhttpClient = Network.getOkhttpObj(false)
        val request = Request.Builder()
            .url(requestUri)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .post(requestBody)
            .build()

        okhttpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(Const.TAG, "GitHub issue creation failed: ${e.message}", e)
                onFailure(e.message ?: "Unknown network error")
            }

            override fun onResponse(call: Call, response: Response) {
                val result = response.body.string()
                if (response.code == 201) {
                    try {
                        val issueResponse = gson.fromJson(result, GitHubIssueResponse::class.java)
                        Log.i(Const.TAG, "GitHub issue created: ${issueResponse.htmlUrl}")
                        onSuccess(issueResponse.htmlUrl)
                    } catch (e: Exception) {
                        Log.e(Const.TAG, "Failed to parse issue response: ${e.message}", e)
                        onFailure("Failed to parse response")
                    }
                } else {
                    Log.e(Const.TAG, "GitHub issue creation error: ${response.code} $result")
                    onFailure("HTTP ${response.code}: $result")
                }
            }
        })
    }

    private fun buildDeviceInfo(): String {
        return """
            |**Device Information:**
            |- **App Version:** ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})
            |- **Device:** ${Build.MANUFACTURER} ${Build.MODEL}
            |- **Android Version:** ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})
            |- **Build:** ${Build.DISPLAY}
        """.trimMargin()
    }

    private fun buildIssueBody(deviceInfo: String, logContent: String): String {
        return """
            |## Auto-generated Issue Report
            |
            |$deviceInfo
            |
            |## Logs
            |
            |```
            |$logContent
            |```
        """.trimMargin()
    }
}
