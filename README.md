# THIN AIR

**HoshiDev Expedition Systems** — native Android climbing. Kotlin + C (NDK) + OpenGL.

Bukan HTML. Bukan WebView. Output-nya **APK**.

## Build lokal

Butuh Android SDK + NDK + JDK 17.

```bash
gradle :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Build di GitHub Actions

Push ke repo. Workflow `.github/workflows/android.yml` nge-build debug + release (debug-signed) lalu upload artifact `thin-air-apk`.

Permission PAT: lihat [`PAT.md`](PAT.md).

## Main

Landscape. Joystick kiri, geser kanan = kamera, tahan **PANJAT**, **LARI**, tenda = checkpoint. Stamina habis / jatuh jauh = respawn di tenda terakhir. Makin tinggi, nafas makin mahal (mesin fisiologi C).

## Stack

| Layer | Bahasa |
|---|---|
| Physiology | **C** (`engine/`) via JNI/NDK |
| Game + UI | **Kotlin** (`app/`) |
| Render | **OpenGL ES 2** |
| Worldgen tables | Python (`sim/`) |
| CI | GitHub Actions → APK |
