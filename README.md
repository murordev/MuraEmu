<div align="center">

# 🍩 MuraEmu1.6

**MuraEmu is a hardcore userspace Android 1.6 (Donut) emulator running directly on modern 64-bit Android devices. No Root required.**

[![Kotlin](https://img.shields.io/badge/Kotlin-1.9+-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack_Compose-UI-4285F4?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com/jetpack/compose)
[![C/C++](https://img.shields.io/badge/C%2FC%2B%2B-NDK-00599C?style=for-the-badge&logo=c%2B%2B&logoColor=white)](#)
[![QEMU](https://img.shields.io/badge/QEMU-ARM_Translation-FF6600?style=for-the-badge&logo=qemu&logoColor=white)](https://www.qemu.org/)

---

### 🚀 Welcome to 2009

MuraEmu is not just an app; it's a complex userspace virtualization environment. It translates 32-bit ARM instructions to 64-bit on the fly, intercepts Kernel IPC (Binder), and renders the raw framebuffer directly to a modern Jetpack Compose Canvas at 60+ FPS.

</div>

## 📖 About The Project

Modern Android devices (like Pixel 7+ and Galaxy S24) have completely dropped support for 32-bit applications (AArch32). **MuraEmu** bypasses this hardware limitation by utilizing a custom-compiled `qemu-arm` translator, a user-mode `binderd` daemon for IPC, and a shared memory shim (`libashmemshim.so`) using `memfd_create`. 

The result? A full Android 1.6 system running smoothly inside an isolated sandbox on Android 14/15+.

---

## ⚠️ Important: First Launch Instructions

Due to the complex nature of the initialization process, there is a known quirk on the very first boot:

1. Press **[ Пуск ]** (Start). You might only see the top status bar (battery/clock) of Android 1.6.
2. Press **[ Стоп ]** (Stop) and wait 2 seconds for the daemons to terminate.
3. Press **[ Пуск ]** (Start) again.
4. The full system will now boot correctly! Use the **[ Разблок ]** (Unlock) button or the D-Pad to unlock the screen.

---

## ✨ Key Features & Architecture

* **🧬 AArch32 to AArch64 Translation:** Runs legacy 32-bit ARM code on pure 64-bit processors.
* **🛡️ Userspace Virtualization:** No root access, custom kernels, or loaded modules required. Everything runs in the app's standard sandbox.
* **🗜️ Ultra-Lightweight ROM:** The entire Android 1.6 system payload is compressed to just ~37 MB.
* **🖼️ Direct Framebuffer Rendering:** Captures `/dev/graphics/fb0` via Memory-Mapped Files (`mmap`) directly to a Compose Bitmap without JNI overhead.
* **🕹️ Retro D-Pad:** Full hardware key emulation (Home, Back, Menu, D-Pad, Trackball) to navigate without relying solely on touch.

---

## 🚧 Current Status: First Beta

**This is a v0.1 Proof-of-Concept Beta.**
It is an experimental playground for reverse engineers and OS developers. Currently:
- The base UI, WindowManager, and Dalvik VM work.
- Touchscreen support is highly experimental.
- Network, Audio, and APK installations are **not implemented/stable yet**.
- Expect crashes, weird bugs, and segmentation faults if you dig too deep.

---

## 📄 License

This project is licensed under the [GPL-3.0 License](LICENSE.txt) (or see custom license details below). Feel free to study the source code, learn about Android internals, and experiment with userspace emulation!

---

<div align="center">

Crafted with care (and a lot of debugging) by **[@murordev](http://t.me/murordev)**

</div>
