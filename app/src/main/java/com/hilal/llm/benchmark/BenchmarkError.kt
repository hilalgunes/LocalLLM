package com.hilal.llm.benchmark

sealed class BenchmarkError(val errorMessage: String) {
    data class Validation(val message: String) : BenchmarkError(message)
    data class Runtime(val message: String) : BenchmarkError(message)
    data class Unknown(val message: String) : BenchmarkError(message)
}
