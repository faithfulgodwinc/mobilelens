# 📱 Mobile Lens Ultra

### **The World's Fastest Real-Time Reading Ruler for Android**
**Experience absolute clarity without the lag.**

---

## 📖 Introduction
Reading long-form content on mobile shouldn't feel like a struggle. **Mobile Lens Ultra** is an ultra-high performance, system-wide magnifying glass designed specifically for modern reading workflows. Whether you're dealing with small fonts, long academic papers, or visual impairments, Mobile Lens provides a sleek, "floating glass" ruler that stays perfectly locked to your content.

## ✨ Key Features
*   **🏎️ Zero-Copy GPU Acceleration**: The first of its kind. Mobile Lens uses direct hardware buffer wrapping to access GPU memory, eliminating the CPU "pixel-copy" lag found in other magnifiers.
*   **💎 Million-Dollar Dashboard**: A premium, world-class Material 3 light-themed interface built for elegance and simplicity.
*   **📏 Focus Reading Ruler**: A widescreen lens optimized specifically for reading full sentences without the need to pan horizontally.
*   **🎩 Auto-Vanish Protocol**: A smart lifecycle engine that "quenches" (terminates) the lens automatically the moment you exit the app or your reading session.
*   **📲 System-Wide Sharing**: Seamlessly connects with Android's system-sharing to focus on exactly the application you need.

## 🛠️ The Performance Engine
Built for speed-critical users, the **X-Vision Engine** under the hood is what sets this project apart from standard accessibility tools:

| Feature | Technology | Benefit |
| :--- | :--- | :--- |
| **Rendering** | `SurfaceView` Backend | 60 FPS lag-free drawing by bypassing the Main UI Thread. |
| **Pipeline** | `wrapHardwareBuffer` | Direct pointer to GPU memory leads to 0% CPU overhead during capture. |
| **Memory** | Multiple Buffer ImageReader | Triple-buffering ensures zero stutter during high-speed scrolling. |
| **Logic** | Background Watchdog | Intelligent self-healing that restores the capture pipe if the system stalls. |

## 🚀 Getting Started

### **System Requirements**
-   **Minimum SDK**: 29 (Android 10)
-   **Target SDK**: 34 (Android 14)
-   **Permissions**: `SYSTEM_ALERT_WINDOW` (Overlay) and `MediaProjection` (Screen Capture).

### **Installation**
1.  Clone this repository.
2.  Open in **Android Studio Hedgehog** or later.
3.  Build and deploy to your physical device.

### **Usage**
1.  Launch **Mobile Lens Ultra**.
2.  Tap the Royal Blue **"LAUNCH MAGNIFIER"** button.
3.  Select **"A single app"** for the most optimized reading experience.
4.  Read with comfort. When finished, simply **Home** or **Back** out of the app, and the lens will vanish on its own.

## 🎨 Professional Design Language
Mobile Lens follows a **"Clean White & Pro Blue"** aesthetic, inspired by high-end modern productivity tools. Every interaction is designed to feel snappier, cleaner, and more professional than a standard system utility.

---

### **Technical Breakdown**
- **Language**: 100% Kotlin
- **Architecture**: Service-based Overlay (Foreground Service)
- **UI Framework**: Material Design 3
- **Graphics Pipeline**: Hardware-level `ImageReader` + `SurfaceView`

---
*Created by the Mobile Lens Engineering Team for high-performance accessibility.*
