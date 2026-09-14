# ONNX CPU denemesi

Test ekranına ONNX eklendi. LiteRT CPU yolu korunuyor. Sınıflandırma ve
retrieval/RAG kodu aynı; dokümanlar kısaltılmıyor. Yeni motor yalnızca
ONNX seçildiğinde yüklenir. Model aynı seçimde bellekte tutulur; her üretim
ayrı generator/KV durumuyla başlar. Native işlemler Dispatchers.Default'tadır.
İptal kontrolü native token üretim çağrıları arasındadır; devam eden tek bir
native çağrıyı anında kesmez.

## Model ve sınırlar

- Runtime: Microsoft ONNX Runtime GenAI Android 0.15.2 + ONNX Runtime Android 1.28.0.
- Paket: justinchuby/gemma-4-e2b-it-onnx, Q4_K_M/default.
- Sabit revision: 9bcf2cb1c2878b1c68a5f94db037272dfb278384.
- İndirme: 8.050.610.282 bayt (~8,05 GB), buna ek çalışma belleği gerekir.
- Decoder quantize olsa da embedding verisi 5,53 GB. Bu tüm modelin 4 bit
  olduğu veya mevcut LiteRT dosyasından küçük olduğu anlamına gelmez.
- Telefon üzerinde model yükleme, cevap doğruluğu, bellek yeterliliği ve hız
  henüz doğrulanmadı. Katalogdaki model bu nedenle Experimental etiketlidir.
- Daha küçük Transformers.js ONNX dosyaları GenAI'nin aynı model sözleşmesine
  sahip değildir; bu motora doğrudan verilemez.

LiteRT model kartı mevcut dağıtım için 2/4/8 bit karışık quantization tarif eder:
https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm
Bu bilgi cihazda daha önce indirilen dosyanın birebir sürümünü kanıtlamaz.
Dolayısıyla karşılaştırma saf runtime/quantization izolasyonu olarak sunulmaz.

## Test

### 14 Eylül: chat template JNI çökmesi

Cihaz logunda `Tokenizer.applyChatTemplate` → `ThrowIfError` → `ThrowNew`
çağrısında geçersiz Modified UTF-8 nedeniyle SIGABRT görüldü. Bu, retrieval
bağlantı zaman aşımından ayrı bir native çökmedir; Kotlin try/catch yakalayamaz.
Bu çağrı kaldırıldı. Sabit model paketinin `chat_template.jinja` dosyasındaki
tek kullanıcı metni, tools/thinking kapalı biçimi Kotlin'de hazırlanıyor:
`<bos><|turn>user\n{trimmed prompt}<turn|>\n<|turn>model\n`.
Yalnızca `model.type=gemma4` kabul edilir; başka modeller JNI'ye girmeden reddedilir.
Dokümanların içeriği kısaltılmaz. Çok mesajlı/multimodal sohbet desteklenmez.
Düzeltme sonrası cihazda gerçek cevap üretimi henüz doğrulanmadı.

1. Uygulamayı normal Android Studio Run ile telefona yükle.
2. Test > ONNX > Model seç > Gemma 4 E2B ONNX Q4_K_M (Experimental).
3. Paket indirildikten sonra aynı soruyu bir kez ısınma için gönder.
4. Aynı soruyu üç kez daha gönder; her seferinde cevap tamamlanmasını bekle.
5. Logcat'te `TEST_FLOW` ve `ONNX_PERF` etiketlerini kaydet.

Ana karşılaştırma: LLM #2'nin TEST_FLOW First text süresi. ONNX_PERF loadMs
model yüklemesini ayrıca belirtir. İlk çalışmayı hazır model ölçümleriyle
karıştırma. Üretilen cevabın dokümandaki koşulları koruduğunu da kontrol et.
ONNX_PERF içindeki ilk metin sayacı şablon/tokenization sonrasında başlar;
LiteRT karşılaştırmasında iki motorda ortak TEST_FLOW sayacını kullan.

Telefon test anında bağlı değildi. Emülatör property sorgularında zaman aşımı
verdiğinden Android native smoke testi çalıştırılamadı. Derleme/birim testi
başarısı, gerçek modelin cihazda üretim yapabildiği anlamına gelmez.

Native yükleme/config kontrolü (model ağırlıklarını indirmez):

```bash
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.hilal.llm.benchmark.OnnxNativeSmokeTest
```
