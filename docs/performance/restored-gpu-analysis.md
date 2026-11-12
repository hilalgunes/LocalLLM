# GPU deneyinden geri alınan analiz aracı

`d53bb60` stash kaydındaki `analyze_opencl_profile.py` geri alındı. Önceden kaydedilmiş OpenCL/Perfetto ölçümlerini inceler; uygulama veya native runtime üzerinde değişiklik yapmaz.

Kullanım: `python3 tools/performance/analyze_opencl_profile.py --input CAPTURE_DIRECTORY --output REPORT_DIRECTORY --trace-processor /path/to/trace_processor`

Girdi tamamlanmış deneyi tanımlayan manifest.json, her koşulun JSON raporu ve aynı isimli .pftrace dosyasıdır. Perfetto trace_processor ayrıca sağlanmalıdır. `--self-check` yalnızca SQL şemasını kontrol eder, cihaz ölçümü üretmez.

Isınma turları istatistiklere katılmaz. Başarısız profiling olayları reddedilir. GPU zamanları ancak aynı kuyruğa ait geçerli cihaz/host çapası ve Perfetto BOOTTIME kaydı varsa hizalanır. Zaman çakışması nedensellik kanıtı değildir.

Üç yeni birim testi ısınmanın dışlanması, başarısız olayın reddi ve saat hizalaması sınırlarını doğrular. Bu adımda gerçek GPU profili toplanmadı; forwarding/probe kodu, eski cihaz logları ve GPU zamanlama yamaları geri alınmadı. Stash korundu.
