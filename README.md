# EMBER

**HoshiDev** — 2D pixel survival. Jaga api. Cari kayu. Jangan kedinginan.

Native Android (Kotlin + Canvas). Bukan HTML. Bukan WebView.

## Main

- Joystick kiri — jalan
- **A** — tebang pohon, ambil berry, nyalakan / isi api (3 kayu = api baru)
- **EAT** — makan berry
- Siang: kumpulin. Malam: dingin + serigala. Dekat api = hangat.
- Lapar atau kedinginan = mati.

## Build APK

```bash
./gradlew :app:assembleDebug
```

GitHub Actions: `.github/workflows/android.yml` → artifact `ember-apk`.
