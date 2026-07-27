package com.her.aimodifier.ai.provider

import android.content.Context
import android.util.Log
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

object ModelListFetcher {
    private const val TAG = "ModelListFetcher"
    private const val ANTHROPIC_VERSION = "2023-06-01"
    private const val API_KEY_LOG_MASK = "API_KEY_HIDDEN"

    private val SENSITIVE_QUERY_PARAMETER_NAMES = setOf(
        "key", "api_key", "apikey", "access_token", "token",
        "authorization", "auth", "password", "secret", "client_secret"
    )

    private val client = UnsafeModelSsl.apply(
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
    ).build()

    fun getModelsListUrl(apiEndpoint: String, apiProviderType: ApiProviderType): String {
        Log.d(TAG, "生成模型列表URL，API端点: ${sanitizeUrlForLog(apiEndpoint)}, 提供商类型: $apiProviderType")

        return when (apiProviderType) {
            ApiProviderType.OPENAI,
            ApiProviderType.OPENAI_GENERIC,
            ApiProviderType.OPENAI_LOCAL -> "${extractBaseUrl(apiEndpoint)}/v1/models"
            ApiProviderType.ANTHROPIC,
            ApiProviderType.ANTHROPIC_GENERIC -> "${extractBaseUrl(apiEndpoint)}/v1/models"
            ApiProviderType.GOOGLE,
            ApiProviderType.GEMINI_GENERIC -> {
                if (apiEndpoint.contains("generativelanguage.googleapis.com")) {
                    if (apiEndpoint.endsWith("/models")) {
                        apiEndpoint
                    } else {
                        val version = if (apiEndpoint.contains("/v1/")) "v1" else "v1beta"
                        "https://generativelanguage.googleapis.com/$version/models"
                    }
                } else {
                    "https://generativelanguage.googleapis.com/v1beta/models"
                }
            }
            ApiProviderType.ZHIPU -> "${extractBaseUrl(apiEndpoint)}/v4/models"
            ApiProviderType.DEEPSEEK,
            ApiProviderType.MOONSHOT,
            ApiProviderType.MIMO,
            ApiProviderType.SILICONFLOW,
            ApiProviderType.IFLOW,
            ApiProviderType.NVIDIA,
            ApiProviderType.BAICHUAN,
            ApiProviderType.OPENROUTER,
            ApiProviderType.FOUR_ROUTER,
            ApiProviderType.NOUS_PORTAL,
            ApiProviderType.INFINIAI,
            ApiProviderType.ALIPAY_BAILING,
            ApiProviderType.LMSTUDIO,
            ApiProviderType.OLLAMA,
            ApiProviderType.PPINFRA,
            ApiProviderType.DOUBAO -> "${extractBaseUrl(apiEndpoint)}/v1/models"
            else -> "${extractBaseUrl(apiEndpoint)}/v1/models"
        }
    }

    private fun sanitizeUrlForLog(rawUrl: String, apiKey: String = ""): String {
        var sanitizedUrl = rawUrl
        if (apiKey.isNotBlank()) {
            sanitizedUrl = sanitizedUrl.replace(apiKey, API_KEY_LOG_MASK)
        }
        SENSITIVE_QUERY_PARAMETER_NAMES.forEach { parameterName ->
            sanitizedUrl = Regex("([?&]${Regex.escape(parameterName)}=)([^&#]*)", RegexOption.IGNORE_CASE)
                .replace(sanitizedUrl) { matchResult ->
                    "${matchResult.groupValues[1]}$API_KEY_LOG_MASK"
                }
        }
        return sanitizedUrl
    }

    private fun extractBaseUrl(fullUrl: String): String {
        return try {
            val url = URL(fullUrl)
            val path = url.path
            val versionPathRegex = Regex("/v\\d+")
            val match = versionPathRegex.find(path)
            if (match != null) {
                val pathBeforeVersion = path.substring(0, match.range.first)
                val finalUrl = "${url.protocol}://${url.authority}$pathBeforeVersion"
                Log.d(TAG, "从 ${sanitizeUrlForLog(fullUrl)} 提取基本URL: ${sanitizeUrlForLog(finalUrl)}")
                finalUrl
            } else {
                val finalUrl = "${url.protocol}://${url.authority}"
                Log.d(TAG, "从 ${sanitizeUrlForLog(fullUrl)} 提取基本URL: ${sanitizeUrlForLog(finalUrl)}")
                finalUrl
            }
        } catch (e: Exception) {
            Log.e(TAG, "URL解析错误: $e")
            fullUrl
        }
    }

    suspend fun getModelsList(
        context: Context,
        apiKey: String,
        apiEndpoint: String,
        apiProviderType: ApiProviderType = ApiProviderType.OPENAI
    ): Result<List<ModelOption>> {
        Log.d(TAG, "开始获取模型列表: 端点=${sanitizeUrlForLog(apiEndpoint, apiKey)}, 提供商=${apiProviderType.name}")

        return withContext(Dispatchers.IO) {
            val maxRetries = 2
            var retryCount = 0
            var lastException: Exception? = null

            while (retryCount <= maxRetries) {
                try {
                    val modelsUrl = getModelsListUrl(apiEndpoint, apiProviderType)

                    Log.d(TAG, "准备发送请求到: ${sanitizeUrlForLog(modelsUrl, apiKey)}, 尝试次数: ${retryCount + 1}/${maxRetries + 1}")

                    val requestBuilder = Request.Builder()
                        .url(modelsUrl)
                        .addHeader("Content-Type", "application/json")

                    when (apiProviderType) {
                        ApiProviderType.GOOGLE,
                        ApiProviderType.GEMINI_GENERIC -> {
                            val urlWithKey = modelsUrl.toHttpUrlSafe()
                                ?.newBuilder()
                                ?.setQueryParameter("key", apiKey)
                                ?.build()
                                ?.toString()
                                ?: modelsUrl
                            requestBuilder.url(urlWithKey)
                        }
                        ApiProviderType.OPENROUTER,
                        ApiProviderType.NOUS_PORTAL -> {
                            if (apiKey.isNotBlank()) {
                                requestBuilder.addHeader("Authorization", "Bearer $apiKey")
                            }
                            requestBuilder.addHeader("HTTP-Referer", "com.her.aimodifier")
                            requestBuilder.addHeader("X-Title", "AI魔改器")
                        }
                        ApiProviderType.MIMO -> {
                            if (apiKey.isNotBlank()) {
                                requestBuilder.addHeader("Authorization", "Bearer $apiKey")
                                requestBuilder.addHeader("api-key", apiKey)
                            }
                        }
                        ApiProviderType.ANTHROPIC,
                        ApiProviderType.ANTHROPIC_GENERIC -> {
                            if (apiKey.isNotBlank()) {
                                requestBuilder.addHeader("x-api-key", apiKey)
                            }
                            requestBuilder.addHeader("anthropic-version", ANTHROPIC_VERSION)
                        }
                        else -> {
                            if (apiKey.isNotBlank()) {
                                requestBuilder.addHeader("Authorization", "Bearer $apiKey")
                            }
                        }
                    }

                    val request = requestBuilder.get().build()
                    val response = client.newCall(request).execute()

                    if (!response.isSuccessful) {
                        val errorBody = response.body?.string() ?: "无错误详情"
                        val responseCode = response.code
                        response.close()

                        if ((apiProviderType == ApiProviderType.OPENAI ||
                                    apiProviderType == ApiProviderType.OPENAI_GENERIC ||
                                    apiProviderType == ApiProviderType.OPENAI_LOCAL ||
                                    apiProviderType == ApiProviderType.DEEPSEEK ||
                                    apiProviderType == ApiProviderType.MOONSHOT ||
                                    apiProviderType == ApiProviderType.MIMO ||
                                    apiProviderType == ApiProviderType.SILICONFLOW ||
                                    apiProviderType == ApiProviderType.IFLOW ||
                                    apiProviderType == ApiProviderType.NVIDIA ||
                                    apiProviderType == ApiProviderType.LMSTUDIO ||
                                    apiProviderType == ApiProviderType.OLLAMA ||
                                    apiProviderType == ApiProviderType.FOUR_ROUTER ||
                                    apiProviderType == ApiProviderType.NOUS_PORTAL) &&
                            modelsUrl.endsWith("/v1/models")
                        ) {
                            val fallbackUrl = modelsUrl.removeSuffix("/v1/models") + "/models"
                            Log.w(TAG, "API请求失败，尝试兼容路径: $fallbackUrl")
                            val fallbackRequest = request.newBuilder().url(fallbackUrl).get().build()
                            val fallbackResponse = client.newCall(fallbackRequest).execute()
                            if (fallbackResponse.isSuccessful) {
                                val fallbackBody = fallbackResponse.body?.string()
                                if (fallbackBody.isNullOrEmpty()) {
                                    fallbackResponse.close()
                                    return@withContext Result.failure(IOException("响应为空"))
                                }
                                fallbackResponse.close()
                                val modelOptions = parseOpenAIModelResponse(fallbackBody)
                                Log.d(TAG, "成功解析模型列表，共获取 ${modelOptions.size} 个模型")
                                return@withContext Result.success(modelOptions)
                            } else {
                                val fallbackErrorBody = fallbackResponse.body?.string() ?: "无错误详情"
                                Log.e(TAG, "兼容路径也失败: 状态码=${fallbackResponse.code}, 错误=$fallbackErrorBody")
                                fallbackResponse.close()
                                return@withContext Result.failure(
                                    IOException("API请求失败: 状态码=${fallbackResponse.code}, 错误=$fallbackErrorBody")
                                )
                            }
                        }

                        Log.e(TAG, "API请求失败: 状态码=$responseCode, 错误=$errorBody")
                        return@withContext Result.failure(IOException("API请求失败: 状态码=$responseCode, 错误=$errorBody"))
                    }

                    val responseBody = response.body?.string()
                    response.close()
                    if (responseBody == null) {
                        Log.e(TAG, "响应体为空")
                        return@withContext Result.failure(IOException("响应体为空"))
                    }

                    Log.d(TAG, "收到响应: ${responseBody.take(200)}${if (responseBody.length > 200) "..." else ""}")

                    val modelOptions = try {
                        when (apiProviderType) {
                            ApiProviderType.OPENAI,
                            ApiProviderType.OPENAI_GENERIC,
                            ApiProviderType.OPENAI_LOCAL,
                            ApiProviderType.DEEPSEEK,
                            ApiProviderType.MOONSHOT,
                            ApiProviderType.MIMO,
                            ApiProviderType.SILICONFLOW,
                            ApiProviderType.IFLOW,
                            ApiProviderType.DOUBAO,
                            ApiProviderType.NVIDIA,
                            ApiProviderType.BAICHUAN,
                            ApiProviderType.OPENROUTER,
                            ApiProviderType.FOUR_ROUTER,
                            ApiProviderType.NOUS_PORTAL,
                            ApiProviderType.INFINIAI,
                            ApiProviderType.ALIPAY_BAILING,
                            ApiProviderType.ZHIPU,
                            ApiProviderType.LMSTUDIO,
                            ApiProviderType.OLLAMA,
                            ApiProviderType.PPINFRA,
                            ApiProviderType.OTHER -> parseOpenAIModelResponse(responseBody)
                            ApiProviderType.ANTHROPIC,
                            ApiProviderType.ANTHROPIC_GENERIC -> parseAnthropicModelResponse(responseBody)
                            ApiProviderType.GOOGLE,
                            ApiProviderType.GEMINI_GENERIC -> parseGoogleModelResponse(responseBody)
                            else -> parseOpenAIModelResponse(responseBody)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "解析响应失败: ${e.message}")
                        return@withContext Result.failure(e)
                    }

                    Log.d(TAG, "成功解析模型列表，共获取 ${modelOptions.size} 个模型")
                    return@withContext Result.success(modelOptions)
                } catch (e: SocketTimeoutException) {
                    lastException = e
                    retryCount++
                    Log.e(TAG, "连接超时: ${e.message}", e)
                    if (retryCount <= maxRetries) {
                        val delayTime = 1000L * retryCount
                        delay(delayTime)
                    }
                } catch (e: IOException) {
                    lastException = e
                    retryCount++
                    Log.e(TAG, "IO异常: ${e.message}", e)
                    if (retryCount <= maxRetries) {
                        val delayTime = 1000L * retryCount
                        delay(delayTime)
                    }
                } catch (e: UnknownHostException) {
                    Log.e(TAG, "无法连接到服务器，域名解析失败", e)
                    return@withContext Result.failure(IOException("无法连接到服务器，域名解析失败", e))
                } catch (e: Exception) {
                    lastException = e
                    retryCount++
                    Log.e(TAG, "获取模型列表失败: ${e.message}", e)
                    if (retryCount <= maxRetries) {
                        val delayTime = 1000L * retryCount
                        delay(delayTime)
                    }
                }
            }

            Log.e(TAG, "超过最大重试次数，获取模型列表失败")
            Result.failure(lastException ?: IOException("获取模型列表失败"))
        }
    }

    private fun parseOpenAIModelResponse(jsonResponse: String): List<ModelOption> {
        val modelList = mutableListOf<ModelOption>()
        try {
            val jsonObject = JSONObject(jsonResponse)
            if (!jsonObject.has("data")) {
                Log.e(TAG, "OpenAI响应格式错误: 缺少'data'字段")
                throw JSONException("响应格式错误: 缺少'data'字段")
            }
            val dataArray = jsonObject.getJSONArray("data")
            Log.d(TAG, "解析OpenAI格式响应: 发现 ${dataArray.length()} 个模型")
            for (i in 0 until dataArray.length()) {
                val modelObj = dataArray.getJSONObject(i)
                val id = modelObj.getString("id")
                modelList.add(ModelOption(id = id, name = id))
            }
        } catch (e: JSONException) {
            Log.e(TAG, "解析OpenAI格式JSON失败: ${e.message}", e)
            throw e
        }
        return modelList.sortedBy { it.id }
    }

    private fun parseAnthropicModelResponse(jsonResponse: String): List<ModelOption> {
        val modelList = mutableListOf<ModelOption>()
        try {
            val jsonObject = JSONObject(jsonResponse)
            val modelsArray = when {
                jsonObject.has("data") -> jsonObject.getJSONArray("data")
                jsonObject.has("models") -> jsonObject.getJSONArray("models")
                else -> {
                    Log.e(TAG, "Anthropic响应格式错误: 缺少'data'或'models'字段")
                    throw JSONException("响应格式错误: 缺少'data'或'models'字段")
                }
            }
            Log.d(TAG, "解析Anthropic格式响应: 发现 ${modelsArray.length()} 个模型")
            for (i in 0 until modelsArray.length()) {
                val modelObj = modelsArray.getJSONObject(i)
                val id = when {
                    modelObj.has("id") -> modelObj.getString("id")
                    modelObj.has("name") -> modelObj.getString("name")
                    else -> continue
                }
                val displayName = modelObj.optString("display_name", id)
                modelList.add(ModelOption(id = id, name = displayName))
            }
        } catch (e: JSONException) {
            Log.e(TAG, "解析Anthropic模型JSON失败: ${e.message}", e)
            throw e
        }
        return modelList.sortedBy { it.id }
    }

    private fun parseGoogleModelResponse(jsonResponse: String): List<ModelOption> {
        val modelList = mutableListOf<ModelOption>()
        try {
            val jsonObject = JSONObject(jsonResponse)
            if (jsonObject.has("models")) {
                val modelsArray = jsonObject.getJSONArray("models")
                Log.d(TAG, "解析Google Gemini API格式响应: 发现 ${modelsArray.length()} 个模型")
                for (i in 0 until modelsArray.length()) {
                    val modelObj = modelsArray.getJSONObject(i)
                    val id = modelObj.getString("name").split("/").last()
                    val displayName = modelObj.optString("displayName", id)
                    val supportedMethods = try {
                        if (modelObj.has("supportedGenerationMethods")) {
                            val methods = modelObj.getJSONArray("supportedGenerationMethods")
                            val methodsList = mutableListOf<String>()
                            for (j in 0 until methods.length()) {
                                methodsList.add(methods.getString(j))
                            }
                            methodsList
                        } else {
                            listOf("generateContent")
                        }
                    } catch (e: Exception) {
                        listOf("generateContent")
                    }
                    if (supportedMethods.contains("generateContent")) {
                        modelList.add(ModelOption(id = id, name = displayName))
                    }
                }
            } else {
                Log.e(TAG, "Google响应格式错误: 未找到'models'字段")
                throw JSONException("响应格式错误: 未找到'models'字段")
            }
        } catch (e: JSONException) {
            Log.e(TAG, "解析Google模型JSON失败: ${e.message}", e)
            throw e
        }
        return modelList.sortedBy { it.id }
    }
}

private fun String.toHttpUrlSafe(): okhttp3.HttpUrl? {
    return try {
        okhttp3.HttpUrl.Companion.toHttpUrlOrNull(this)
    } catch (e: Exception) {
        null
    }
}
