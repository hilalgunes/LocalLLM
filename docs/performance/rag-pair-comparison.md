# Selectable two-model RAG comparison

Open Compare → Embedding sayfasını aç → Metin → Metin. Choose two supported embedding models, optionally one common generation model, top-k, repeat count and EmbeddingGemma output dimension. Download missing models inside the comparison screen. The default dataset is eight Turkish passages/queries with relevance labels and reference answers. It includes hard negatives (annual leave notice vs annual leave entitlement). Imported RAG chunks or editable custom test data can be used; queries use `question | relevant passage numbers | reference answer`.

Both EmbeddingGemma 2 Text 270M and 740M are integrated in the normal RAG pipeline with LiteRT-LM EmbeddingEngine 0.18.0, normalized float32 vectors, CPU / four threads / 2048 input tokens. The 740M package shares the text backbone and also contains vision/audio components; this benchmark uses only text. It does not infer that the 740M text model must be more accurate. Sources:

- https://huggingface.co/litert-community/embeddinggemma-2-text-270m-litert-lm
- https://huggingface.co/litert-community/embeddinggemma-2-740m-litert-lm
- https://github.com/google-ai-edge/LiteRT-LM/blob/main/kotlin/java/com/google/ai/edge/litertlm/EmbeddingEngine.kt

The SDK upgrade requires Kotlin 2.3.20 / KSP 2.3.12 and replaces the old local low-priority 0.16.1 AAR. This no longer contains that custom low-priority patch. Existing LLM token limits use the new `maxOutputToken` API.

## Measurements and fairness

Each embedding round uses a fresh provider, explicit initialization measurement, first embedding, two excluded query warmups, passage vector generation and all query embeddings/searches. Round order alternates AB/BA. Model providers are closed before the answer phase. Disk caches are not flushed, so initialization is not a cold-OS benchmark. Exact cosine similarity against the same passages controls retrieval implementation; sqlite-vec and Graph RAG are not benchmarked here. Native backends/thread/context limits differ for other selectable models and are documented in the report. Gecko's 320-character limit is validated to prevent comparing silently truncated inputs. Known unsafe E5-small/MiniLM GGUF native loaders remain excluded.

Report: initialization/first embedding/indexing samples, query median and nearest-rank p95, separate search time, throughput, actual file size, vector dimension/norm/storage, total-process baseline PSS and sampled maximum PSS every 200 ms. PSS is not isolated model RAM or a guaranteed instantaneous peak. Full source rankings, cosine scores and binary relevance judgments accompany each query. Hit@k, precision@k, recall@k, MRR over the full ranking and binary nDCG@k are calculated against user-supplied relevance labels.

One common generation model receives the same prompt template but the respective model's selected sources. Query order alternates AB/BA. LiteRT retains one warmed engine, creates a fresh conversation per request, and uses the existing sampler (temperature 0.7, seed 0) with a 192-token cap. Other generation backends use their normal lifecycle, which may include per-request initialization. Time to first nonempty text and total generation time are measured. These are generation-stage timings, not an end-to-end RAG request measurement. Generation is once per question/model, not repeated with the embedding timing rounds.

Reference-token F1 reports lexical overlap, not truth. Human correctness ratings (correct/partial/incorrect) are stored per answer and summarized separately. This is deliberately not an automated factual correctness judge; references and relevant-source annotations must be authored correctly.

JSON export includes raw measurements, dataset/settings, model configs, file SHA-256 fingerprints, method notes, rankings, reference answers, generated text and human ratings. The latest completed report and its manual ratings are stored in app files as `rag-pair-comparison.json` and restored when the screen is reopened. Failed native initialization/outputs are reported as errors, not fabricated embeddings/answers. Cancelled runs do not publish a completed report.

## Validation

`RagComparisonMetricsTest` validates relevance formulas, missing sources, ideal ranking, reference-overlap limitations and invalid annotations. `RagPairComparisonSmokeTest` runs both real Gemma bundles at 128 dimensions with a real shared Gemma 4 E2B answer model and verifies timings, source-label consistency, generated answers and JSON ratings. Model accuracy is not asserted to be perfect: a wrong first-ranked source is a legitimate comparison result.

Use Gradle to assemble the debug app and instrumentation APK, then install with `adb install -r` and run instrumentation directly to retain downloaded device models. The connected Gradle test task may uninstall the app as cleanup.

Validated 2026-10-13 on Samsung SM-N975F / Android 12: unit tests and real-model smoke test passed. The archived smoke report uses 3 passages, 1 query, top-1, 128 dimensions and 1 timing round; it verifies plumbing, not a general model ranking. Both models selected the leave-entitlement passage for a notice-period query and the shared answer model correctly reported that the selected source lacked notice-period information. The report preserves this retrieval failure rather than assuming perfect model accuracy.
