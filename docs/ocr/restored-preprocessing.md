# OCR deneyinden geri alınan görüntü hazırlama

`d53bb60` stash kaydından yalnızca `DetaiLineImage` geri alındı. Tek satırlı, düz zeminli görüntülerin kenar boşluklarını kırpar; koyu zemini ters çevirir. Metin algılama veya OCR tanıma yapmaz. Henüz uygulama ekranına bağlanmadı.

Kaynak bitmap değiştirilmez veya recycle edilmez. Sonuç kaynakla aynı nesne olabilir; çağıran yalnızca kendisine ait bitmapleri, kimliklerini karşılaştırarak bırakmalıdır.

Üç instrumentation testi Samsung SM-N975F ve Pixel 10 Pro üzerinde geçti: kırpma, ters çevirme, saydam/boş/küçük görüntü ve kaynak sahipliği.

Eski OCR model dosyaları (yaklaşık 88 ve 112 MB), tanıma motoru, PDF hattı ve ekran stash içinde korunur. Model dağıtım/lisans ve katalog entegrasyonu çözülmeden bunlar uygulamaya eklenmedi; çalışan OCR özelliği olarak sunulmaz.
