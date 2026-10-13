package com.hilal.llm.test

import com.google.gson.Gson
import com.hilal.llm.test.network.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

class JevClassifierTest {
    private fun response(choice: String = "EVET") = JevResponse(
        "jev-test", mapOf("is_hr" to JevAnswer("choice", choice,
            if (choice == "EVET") mapOf("EVET" to 0.9, "HAYIR" to 0.1)
            else mapOf("EVET" to 0.1, "HAYIR" to 0.9), 0.7))
    )

    @Test fun localRemainsDefault() {
        assertEquals(ClassificationProvider.LOCAL, TestUiState().classificationProvider)
    }

    @Test fun readsBothRoutingDecisions() {
        assertEquals("EVET", parseJevResponse(response()).result)
        assertEquals("HAYIR", parseJevResponse(response("HAYIR")).result)
        assertEquals(0.9, parseJevResponse(response()).hrProbability, 0.0001)
    }

    @Test fun invalidResponsesNeverBecomeNonHr() {
        val valid = response().answers!!.getValue("is_hr")
        val invalid = listOf(null, JevResponse(), response("UNKNOWN"),
            JevResponse("jev", mapOf("is_hr" to valid.copy(confidence = Double.NaN))),
            JevResponse("jev", mapOf("is_hr" to valid.copy(type = "noul"))),
            JevResponse("jev", mapOf("is_hr" to valid.copy(choice = "HAYIR"))),
            JevResponse("jev", mapOf("is_hr" to valid.copy(probabilities = mapOf("EVET" to 1.5, "HAYIR" to -0.5)))),
            JevResponse("jev", mapOf("is_hr" to valid.copy(probabilities = mapOf("EVET" to 0.1, "HAYIR" to 0.1)))),
            Gson().fromJson("""{"model":"jev","answers":{"is_hr":{"type":"choice","choice":"EVET"}}}""", JevResponse::class.java))
        invalid.forEach { value -> assertThrows(JevException::class.java) { parseJevResponse(value) } }
    }

    @Test fun sendsDocumentedRequestAndSeparatesKeyFromBody() = runBlocking {
        var calls = 0
        val api = object : JevApi {
            override suspend fun evaluate(authorization: String, request: JevRequest): Response<JevResponse> {
                calls++
                assertEquals("Bearer test-key", authorization)
                assertEquals("Yıllık izin kaç gün?", request.state)
                assertEquals("jev-latest", request.model)
                assertEquals(setOf("EVET", "HAYIR"), request.questions.getValue("is_hr").criteria.keys)
                assertFalse(Gson().toJson(request).contains("test-key"))
                return Response.success(response())
            }
        }
        assertEquals("EVET", JevClassifier(api).classify("Yıllık izin kaç gün?", " test-key ").result)
        assertEquals(1, calls)
    }

    @Test fun errorsDoNotLeakProviderBodyOrTriggerAnotherRequest() {
        var calls = 0
        val api = object : JevApi {
            override suspend fun evaluate(authorization: String, request: JevRequest): Response<JevResponse> {
                calls++
                return Response.error(401, "secret-provider-body".toResponseBody())
            }
        }
        val error = assertThrows(JevException::class.java) {
            runBlocking { JevClassifier(api).classify("Soru", "test-key") }
        }
        assertFalse(error.message!!.contains("secret-provider-body"))
        assertEquals(1, calls)
    }

    @Test fun blankKeyDoesNotCallProviderAndCancellationPropagates() {
        var calls = 0
        val api = object : JevApi {
            override suspend fun evaluate(authorization: String, request: JevRequest): Response<JevResponse> {
                calls++
                throw CancellationException("cancelled")
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { JevClassifier(api).classify("Soru", " ") }
        }
        assertEquals(0, calls)
        assertThrows(CancellationException::class.java) {
            runBlocking { JevClassifier(api).classify("Soru", "test-key") }
        }
        assertEquals(1, calls)
    }
}
