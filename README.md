# Gemma 3 Android Word Coach 📱🧠

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF.svg?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Android](https://img.shields.io/badge/Android-API%2026%2B-3DDC84.svg?logo=android&logoColor=white)](https://developer.android.com)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4.svg?logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![Model](https://img.shields.io/badge/Model-Gemma%203%201B%20IT%20(INT4)-FF6F00.svg)](https://huggingface.co/litert-community/Gemma3-1B-IT)
[![Engine](https://img.shields.io/badge/Engine-Google%20LiteRT--LM%200.17.1-blue.svg)](https://ai.google.dev/edge/litert)

An offline, on-device AI language learning game for Android powered by **Google Gemma 3 1B Instruct** running locally via **Google LiteRT-LM** (formerly TensorFlow Lite / MediaPipe GenAI). 

Test vocabulary, guess semantic word associations, and get instant, context-aware linguistic explanations—**100% on your smartphone, without sending private data or prompts to any remote server**.

---

## ✨ Features

- **🚀 100% On-Device Neural Inference**: Runs entirely locally on your smartphone processor using Google's LiteRT runtime. No subscription, zero API costs, and full offline functionality once downloaded.
- **🎮 Word Coach Vocabulary Game**: Generates endless multiple-choice quiz questions focusing on subtle synonyms, vocabulary nuances, and word meanings (5 questions per round).
- **⚡ Zero-Latency Background Prefetching**: Utilizes asynchronous Kotlin Coroutines (`Deferred<WordCard>`) to pre-generate the next question in the background while the user answers the current one.
- **💡 "Learn Why" (Deep Explanations)**: Tap any completed card to generate an on-demand, nuanced linguistic explanation explaining why words are related.
- **🔐 Secure Hugging Face Token Gate**: Seamless download experience for gated models. Paste your token once; it is stored safely in Android `Encrypted/Private SharedPreferences` and verified via pre-flight HTTP probe.
- **🎨 Modern Material 3 UI**: Built from the ground up with Jetpack Compose, edge-to-edge layout, responsive progress tracking, and Russian + English localization.

---

## 📱 Tested & Verified Devices

The app and INT4-quantized model have been tested and verified to deliver smooth, stable performance on accessible mobile hardware:

| Device | Chipset | RAM | Performance |
| :--- | :--- | :--- | :--- |
| **Xiaomi Redmi Note 10** | Qualcomm Snapdragon 678 | 4 GB | Smooth (~15-20 tok/s), low thermal footprint |
| **Tecno Spark 10 Pro** | MediaTek Helio G88 | 8 GB | Smooth execution, responsive UI |

---

## 🧠 Model Specifications

| Parameter | Value |
| :--- | :--- |
| **Model Family** | Google Gemma 3 (1 Billion parameters, Instruct-tuned) |
| **Quantization** | INT4 (`litertlm` optimized container) |
| **File Name** | `gemma3-1b-it-int4.litertlm` (~550 MB) |
| **Hugging Face Repository** | [`litert-community/Gemma3-1B-IT`](https://huggingface.co/litert-community/Gemma3-1B-IT) |
| **Runtime Engine** | `com.google.ai.edge.litertlm:litertlm-android:0.17.1` |
| **Sampling Configurations** | • Card Generation: `temperature = 0.8`, `topK = 40`<br>• Tutor Explanations: `temperature = 0.3`, `topK = 40` |

---

## 🏗️ Architecture & Data Flow

```
┌─────────────────────────────────────────────────────────────┐
│                    Jetpack Compose UI                       │
│  - TokenGateContent (Hugging Face Auth & Validation)        │
│  - DownloadContent (Resumable OkHttp Stream & Progress)     │
│  - QuestionContent (Active Quiz Card & Options)             │
│  - ResultsContent (Scoreboard & "Learn Why" Explanations)   │
└──────────────────────────────┬──────────────────────────────┘
                               │ Observes State & Events
                               ▼
┌─────────────────────────────────────────────────────────────┐
│                    WordCoachViewModel                       │
│  - Pre-flight HF Token Probe (HEAD https://huggingface.co)  │
│  - OkHttp Resumable Downloader (app.filesDir)               │
│  - Dual ConversationConfig Management (Creative vs Precise) │
│  - Asynchronous Background Prefetching Queue                │
└──────────────────────────────┬──────────────────────────────┘
                               │ Mutex-Guarded Engine Access
                               ▼
┌─────────────────────────────────────────────────────────────┐
│               Google LiteRT-LM Native Engine                │
│             (liblitertlm_jni.so / ARM64 & x86)              │
│                                                             │
│       ┌──────────────────────────────────────────────┐      │
│       │   gemma3-1b-it-int4.litertlm (Local Weights) │      │
│       └──────────────────────────────────────────────┘      │
└─────────────────────────────────────────────────────────────┘
```

### Key Technical Design Decisions

1. **Mutex Serialization (`gemmaMutex = Mutex()`)**: LLM weight access and context buffers cannot be evaluated simultaneously from concurrent threads. The ViewModel synchronizes question generation and explanation requests via coroutine mutex locks.
2. **Dual-Temperature Sampling**:
   - Question cards require lexical variety and creative distractors $\rightarrow$ `temperature = 0.8f`.
   - Explanations require factual linguistic accuracy and grounding $\rightarrow$ `temperature = 0.3f`.
3. **Optimized Model Packaging**: `build.gradle.kts` specifies `androidResources { noCompress.add("litertlm") }` to prevent Android package compressors from corrupting memory-mapped model weights.

---

## 🚀 Getting Started

### Option 1: Install Pre-Built APK (Fastest)

1. Go to the [Releases](https://github.com/golaso024/gemma2android/releases) section of this repository.
2. Download `Gemma3-Android-v1.0.0.apk`.
3. Install the APK on your Android device (ensure "Install unknown apps" permission is granted).
4. Launch **LanguageLLM / Word Coach**.
5. When prompted, paste your [Hugging Face Read Token](https://huggingface.co/settings/tokens) (make sure you accepted the terms on the [`Gemma3-1B-IT`](https://huggingface.co/litert-community/Gemma3-1B-IT) repo page).
6. Let the model download (~550 MB) and enjoy playing!

### Option 2: Build from Source

1. Clone the repository:
   ```bash
   git clone https://github.com/golaso024/gemma2android.git
   cd gemma2android
   ```
2. Open the project in **Android Studio Ladybug (2024.2+)** or newer.
3. Allow Gradle to sync dependencies via `gradle/libs.versions.toml`.
4. Connect an Android device with USB debugging enabled (API 26+).
5. Build and run:
   ```bash
   ./gradlew assembleDebug
   ```

---

## 📂 Project Structure

```
gemma2android/
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── java/com/salamander/languagellm/
│   │   │   ├── MainActivity.kt           # Edge-to-edge entry point
│   │   │   ├── WordCard.kt               # WordCard data model
│   │   │   ├── WordCoachScreen.kt        # Jetpack Compose UI & navigation
│   │   │   ├── WordCoachViewModel.kt     # LiteRT engine, download & game state
│   │   │   └── ui/theme/                 # Material 3 Color, Theme & Typography
│   │   └── res/                          # Vector drawables, strings (EN & RU)
│   ├── build.gradle.kts                  # App-level dependencies & LiteRT config
│   └── proguard-rules.pro
├── gradle/
│   └── libs.versions.toml                # Centralized Gradle version catalog
├── build.gradle.kts                      # Root Gradle configuration
├── settings.gradle.kts                   # Project repositories and settings
├── gradle.properties                     # JVM & AndroidX optimization flags
└── README.md
```

---

## 📄 License & Attribution

- Built with Google's open-weights [Gemma 3](https://ai.google.dev/gemma) model.
- Model weights subject to the [Gemma Terms of Use](https://ai.google.dev/gemma/terms).
- Developed for learning, experimentation, and edge AI democratization.
