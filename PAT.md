# PAT buat upload + build APK

Jangan kasih PAT classic “no expiry + all scopes”. Bikin **fine-grained** (atau classic sempit), expire 7 hari.

## Fine-grained PAT (disarankan)

1. GitHub → Settings → Developer settings → Personal access tokens → **Fine-grained tokens**
2. Repository access: **Only select repositories** → repo yang mau diisi (boleh repo baru kosong)
3. Permissions:

| Permission | Access | Kenapa |
|---|---|---|
| **Metadata** | Read | Wajib |
| **Contents** | Read and write | Push source (Kotlin, C, Gradle, assets) |
| **Workflows** | Read and write | Push `.github/workflows/android.yml` |
| **Actions** | Read and write | Liat run + download artifact APK |

Tidak perlu: Administration, Secrets, Variables, Delete repo, Packages, Pull requests (kecuali mau gue bikin PR).

## Classic PAT (kalau fine-grained ribet)

Centang **hanya**:

- `repo`
- `workflow`

`workflow` wajib. Tanpa itu push file Actions ditolak.

## Yang gue butuh dari lu

1. URL repo, contoh `https://github.com/USERNAME/thin-air`
2. PAT (kirim sekali, jangan commit)
3. Branch tujuan (`main` / `master`)

Gue push semua file, Actions nge-build, artifact-nya `thin-air-apk` (file `.apk` siap sideload).

Setelah selesai: **revoke PAT-nya**.
