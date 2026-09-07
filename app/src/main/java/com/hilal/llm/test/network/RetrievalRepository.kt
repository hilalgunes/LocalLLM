package com.hilal.llm.test.network

import android.util.Log
import com.hilal.llm.BuildConfig
import com.google.gson.JsonParseException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit

class RetrievalRepository(
    private val service: RetrievalApiService = RetrievalNetwork.service
) {
    suspend fun query(question: String): RetrievalResult {
        val response = try {
            service.query(RetrievalQueryRequest(query = question))
        } catch (error: ConnectException) {
            throw RetrievalException.Connection(error)
        } catch (error: SocketTimeoutException) {
            throw RetrievalException.Timeout(error)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: JsonParseException) {
            throw RetrievalException.Parse(error)
        } catch (error: Throwable) {
            throw RetrievalException.Network(error)
        }

        if (!response.isSuccessful) {
            throw RetrievalException.Http(response.code())
        }
        val body = response.body()
            ?: throw RetrievalException.Parse(IllegalStateException("Response body is empty"))
        val chunks = body.runs.orEmpty()
            .flatMap { it.results.orEmpty() }
            .mapNotNull { item ->
                item.text?.trim()?.takeIf(String::isNotEmpty)?.let { text ->
                    RetrievedChunk(text = text, source = item.documentName, score = item.score)
                }
            }
        return RetrievalResult(chunks = chunks)
    }
}

sealed class RetrievalException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Connection(cause: Throwable) : RetrievalException("Retrieval servisine ulaşılamadı.", cause)
    class Timeout(cause: Throwable) : RetrievalException("Retrieval isteği zaman aşımına uğradı.", cause)
    class Http(val statusCode: Int) : RetrievalException(
        if (statusCode in 400..499) "Retrieval servisi isteği reddetti (HTTP $statusCode)."
        else "Retrieval servisinde sunucu hatası oluştu (HTTP $statusCode)."
    )
    class Parse(cause: Throwable) : RetrievalException("Retrieval yanıtı okunamadı.", cause)
    class Network(cause: Throwable) : RetrievalException("Retrieval bağlantısı başarısız oldu.", cause)
}

private object RetrievalNetwork {
    private const val BASE_URL = "http://192.168.2.39:11000/"

    private val loggingInterceptor = HttpLoggingInterceptor { message ->
        Log.d("TEST_HTTP", message)
    }.apply {
        level = if (BuildConfig.DEBUG) {
            HttpLoggingInterceptor.Level.BODY
        } else {
            HttpLoggingInterceptor.Level.NONE
        }
    }

    private val client = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    val service: RetrievalApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create())
            .build()
            .create(RetrievalApiService::class.java)
    }
}
