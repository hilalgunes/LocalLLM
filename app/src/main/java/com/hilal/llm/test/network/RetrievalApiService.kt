package com.hilal.llm.test.network

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

interface RetrievalApiService {
    @POST("api/v1/retrieval/query")
    suspend fun query(
        @Body request: RetrievalQueryRequest
    ): Response<RetrievalQueryResponse>
}
