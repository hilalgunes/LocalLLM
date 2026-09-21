# LiteRT GPU Low önceliği

LiteRT motoru `Backend.GPU()` kullanır. Debug ve release derlemeleri
`app/libs/litertlm-0.16.1-low-priority.aar` dosyasına bağlıdır.

Bu AAR, LiteRT-LM v0.16.1 kaynaklarından derlenen JNI ile ana executor için
`gpu_context_low_priority=true` ayarını uygular. Kaynak değişikliği
`litert-low-priority.patch` dosyasındadır. Kotlin classes.jar resmî AAR'dan,
arm64 JNI özel derlemeden, GPU/Gemma bağımlılıkları aynı sürümün prebuilts
dosyalarından alınmıştır. Özel native derleme arm64 içindir.

Manifestteki native kütüphane bildirimleri GPU erişimi için korunmuştur.
