package com.hilal.llm.benchmark

/** Single user text turn, no tools/thinking: matches the pinned Gemma 4 chat_template.jinja.
 * Avoids GenAI 0.15.2's native applyChatTemplate path, which aborts on this device.
 */
internal object OnnxChatPrompt {
    fun format(modelType: String, prompt: String): String {
        require(modelType == "gemma4") { "Unsupported ONNX chat format: $modelType" }
        return "<bos><|turn>user\n${prompt.trim()}<turn|>\n<|turn>model\n"
    }
}
