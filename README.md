<h1 align="center">Deepslate Launcher</h1>

<p align="center">
  <img src="app_pojavlauncher/src/main/assets/deepslate.png" width="130" height="130" alt="Deepslate Launcher Logo"><br>
  <i>A sleek, dark-themed Minecraft: Java Edition launcher for Android based on PojavLauncher & Amethyst, built for freedom, performance, and deep customization.</i>
</p>

<p align="center">
  <a href="https://github.com/zacxyonly/Deepslate-Android/actions"><img src="https://github.com/zacxyonly/Deepslate-Android/actions/workflows/android.yml/badge.svg" alt="Build Deepslate Launcher"></a>
  <img src="https://img.shields.io/badge/Platform-Android-green.svg" alt="Platform Android">
  <img src="https://img.shields.io/badge/Theme-Dark-18181b.svg" alt="Theme Dark">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-LGPL--3.0-blue.svg" alt="License LGPLv3"></a>
</p>

---

## 🌟 Highlights & Key Features

* 🔓 **Unlocked Local Authentication:** Bebas membuat dan menggunakan akun lokal (offline) langsung dari awal tanpa harus memiliki akun Microsoft resmi terlebih dahulu.
* 📦 **Unrestricted Downloads:** Pengunduhan game vanilla, library, dan aset Minecraft tetap berfungsi penuh dan lancar pada akun lokal saat terhubung ke internet.
* 🧩 **Full Modloader & Custom JAR Support:** Mendukung instalasi Forge, Fabric, NeoForge, Quilt, LWJGL3ify, serta custom installer `.jar` langsung dari launcher.
* 🛡️ **Consistent Signing Keystore:** Seluruh build APK (baik Release maupun Debug) ditandatangani dengan keystore permanen yang konsisten, mencegah error *conflicting signature* saat update aplikasi di Android.
* 🌒 **Dark Aesthetic:** Didesain dengan tema gelap modern yang nyaman di mata untuk sesi bermain panjang.
* 🆔 **Valid Offline UUID:** Otomatis menghasilkan UUID standar Minecraft offline berbasis username, menjaga data inventory, achievement, dan kompatibilitas server LAN tetap aman.
* ⚙️ **Manual & Auto GitHub Actions:** Workflow CI siap pakai dengan opsi build manual (`workflow_dispatch`) untuk meng-compile APK Debug, Release, atau keduanya langsung di GitHub Actions.

---

## 📥 Getting Deepslate Launcher

### 1. Download via GitHub Actions (Recommended)
1. Buka tab **[Actions](https://github.com/zacxyonly/Deepslate-Android/actions)** pada repositori ini.
2. Pilih workflow run terbaru yang berhasil (tanda centang hijau).
3. Scroll ke bagian **Artifacts** di bagian bawah halaman untuk mengunduh:
   * `deepslate-release` (APK Release siap instal)
   * `deepslate-debug` (APK Debug)

### 2. Manual Build via GitHub Actions
Kamu bisa men-trigger build secara manual kapan saja:
1. Buka tab **[Actions](https://github.com/zacxyonly/Deepslate-Android/actions)** -> **Build Deepslate Launcher**.
2. Klik **Run workflow**.
3. Pilih tipe build (`all`, `release`, atau `debug`) dan jalankan.

---

## 🔨 Building Locally

### Prasyarat
* Linux / macOS / Windows (WSL2 direkomendasikan)
* JDK 21 ([Temurin](https://adoptium.net/temurin/releases) atau OpenJDK)
* Android SDK (compileSdk 37, ndkVersion 27.3.13750724)
* Pastikan path direktori tidak mengandung spasi.

### Langkah Build
```bash
# Clone beserta seluruh submodule
git clone --recursive https://github.com/zacxyonly/Deepslate-Android.git
cd Deepslate-Android

# Jalankan script persiapan bahasa
chmod +x ./scripts/*.sh
./scripts/languagelist_updater.sh

# Build Release APK
./gradlew :app_pojavlauncher:assembleRelease

# Atau build Debug APK
./gradlew :app_pojavlauncher:assembleDebug
```
Output APK akan berada di:
* `app_pojavlauncher/build/outputs/apk/release/app_pojavlauncher-release.apk`
* `app_pojavlauncher/build/outputs/apk/debug/app_pojavlauncher-debug.apk`

---

## 🗺️ Roadmap & Rencana Fitur
- [x] Bypass pembatasan akun Microsoft (Bebas Local Account).
- [x] Penandatanganan konsisten dengan Deepslate Keystore.
- [x] Workflow CI GitHub Actions mandiri dengan manual trigger.
- [ ] Penggantian icon aplikasi dengan tema Deepslate Dark.
- [ ] Kustomisasi tampilan UI tema dark / AMOLED.
- [ ] Optimasi performa render engine & integrasi mod performa.
- [ ] Fitur kustom HUD dan controller mobile lanjutan.

---

## 📜 License

Deepslate Launcher dilisensikan di bawah **[GNU Lesser General Public License v3.0 (LGPL-3.0)](LICENSE)**.

---

## 🤝 Credits & Acknowledgments

Deepslate Launcher dibangun atas fondasi karya hebat komunitas open-source:
* **[Amethyst-Android](https://github.com/AngelAuraMC/Amethyst-Android)** oleh tim AngelAuraMC.
* **[PojavLauncher](https://github.com/PojavLauncherTeam/PojavLauncher)** oleh tim PojavLauncher.
* **[Boardwalk](https://github.com/zhuowei/Boardwalk)** oleh zhuowei.
* Seluruh pustaka pihak ketiga: OpenJDK Mobile, GL4ES, MobileGlues, SDL3, ANGLE, dan OpenAL-Soft.
