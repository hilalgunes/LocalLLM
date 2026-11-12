# Android native 16 KB kontrolü — 12 Kasım 2026

`python3 tools/performance/check_native_alignment.py app/build/outputs/apk/debug/app-debug.apk`

Araç arm64 ELF LOAD segmentlerini ve sıkıştırılmamış APK girdilerinin hizalamasını kontrol eder. Uyumsuzluk varsa çıkış kodu 1 olur. Bu statik kontrol tüm runtime işlemlerinin çalıştığını kanıtlamaz.

## Sonuç

- NDK 27 ile derlenen kendi `librag_sqlitevec.so` kütüphanemize 16 KB max/common page linker seçenekleri eklendi.
- Debug APK içindeki 20 arm64 kütüphanenin 19'u hizalama kontrolünü geçti. Ayrıntılar `native-alignment-2026-11-12.json` içinde.
- Kalan `libmat.so`, `app/libs/onnxruntime-genai-android-0.15.2.aar` içinden geliyor. `libonnxruntime-genai.so` bu dosyayı DT_NEEDED olarak istediği için paketten çıkarmak güvenli bir çözüm değildir.
- Tam uyumluluk için bu bağımlılığın 16 KB hizalı derlemesi veya doğrulanmış yeni AAR sürümü gerekiyor. Uygulamanın tamamı 16 KB uyumlu olarak işaretlenmedi.

## Doğrulama

Debug APK derlendi. `RagVectorStorePersistenceTest` Samsung SM-N975F ve 16 KB Pixel 10 Pro emülatöründe geçti. ELF kontrol aracının üç birim testi geçti. Bu test ONNX GenAI ile model üretimini kapsamıyor.

Kaynak: [Android 16 KB rehberi](https://developer.android.com/guide/practices/page-sizes).
