# Milestone 1: Native feasibility evidence

**Status:** Pending physical-device evidence. The Linux build/static gate passes for Android, but this milestone does not pass until the iOS build and both device matrices below pass.

This probe is feasibility code, not the package's eventual public API. It performs inference entirely in native-owned asynchronous workers, has no inference-time downloader or cloud fallback, and preserves the approved lifecycle policy: backgrounding pauses queued starts, does not blanket-cancel active work, and foregrounding resumes queue processing.

## Pinned sources

| Component | Pin | Authoritative source inspected |
| --- | --- | --- |
| NativePHP Mobile | `4.5.1`, `19e421ba591d6dda1a2ea7cb220b61329d4b2614` | [`NativePHP/mobile-air`](https://github.com/NativePHP/mobile-air/tree/19e421ba591d6dda1a2ea7cb220b61329d4b2614) |
| NativePHP starter | `45bad117eac610ce2efcd5f74112d13ff202b28f` | [`NativePHP/mobile-starter`](https://github.com/NativePHP/mobile-starter/tree/45bad117eac610ce2efcd5f74112d13ff202b28f) |
| MLX Swift LM | `3.31.4`, `bd4b7434e6bdb588c7ef55706ff8904cb7fd4c57` | [`ml-explore/mlx-swift-lm`](https://github.com/ml-explore/mlx-swift-lm/tree/bd4b7434e6bdb588c7ef55706ff8904cb7fd4c57) |
| MLX Swift | `0.31.6`, `0bb916c67f4b9e5c682cbe02a42c701c93ab5021` | [`ml-explore/mlx-swift`](https://github.com/ml-explore/mlx-swift/tree/0bb916c67f4b9e5c682cbe02a42c701c93ab5021) |
| Swift Transformers | `1.3.4`, `c21fdcde390313a6d98d8e33a346f2c3486c3ab0` | [`huggingface/swift-transformers`](https://github.com/huggingface/swift-transformers/tree/c21fdcde390313a6d98d8e33a346f2c3486c3ab0) |
| llama.cpp | `b11056`, `e613ef2c81bae98d59850d061ac29e6e3e88cb00` | [`ggml-org/llama.cpp`](https://github.com/ggml-org/llama.cpp/tree/e613ef2c81bae98d59850d061ac29e6e3e88cb00) |

NativePHP 4.5.1 discovers the probe through `NativeServiceProvider`, copies native sources, generates bridge registration, and injects Gradle/SPM declarations during `native:run` or `native:package` (not `native:install`). Native bridge calls are synchronous on the PHP caller, so `Start` only validates/enqueues and returns; inference is owned by a Swift actor or Kotlin coroutine/channel. Native event emission was checked against the v4 Android `NativeElementBridge.sendNativeEvent` and iOS `LaravelBridge.shared.send` implementations rather than the WebView-era wording in the event documentation.

The local iOS load path uses `loadModelContainer(from:using:)` and `AutoTokenizer.from(modelFolder:)`; the pinned source reads local config, weights, and tokenizer files. It does not instantiate the hub downloader. The exact MLX API still requires a Mac compile. In particular, `mlx-swift` 0.31.6 declares `swift-tools-version: 6.3;(experimentalCGen)`, so the Mac must use an Xcode toolchain that actually accepts that manifest; do not assume Xcode 16 is sufficient.

The Android integration publishes the pinned llama.cpp Android library as an AAR to a generated local Maven repository. The patch lowers the native library minimum to API 29, compiles its Kotlin surface with NativePHP's Kotlin 2.0.0, keeps CPU as the baseline, adds raw/chat selection, and implements cooperative cancellation through an atomic flag, llama abort callback, prompt-batch checks, and decode checks. The probe releases the Android model after every terminal request so raw and chat state cannot contaminate each other and a late native abort flag cannot affect reuse; the next request reloads locally. Vulkan is not enabled by this gate.

## Matched model manifest

The complete file-level manifest is [`models/qwen2-0.5b-instruct.json`](../../../models/qwen2-0.5b-instruct.json). Both artifacts are official Qwen **Qwen2-0.5B-Instruct** conversions—Qwen2.5 is not mixed in.

| Platform | Repository and immutable revision | Main weights | Verified bundle size |
| --- | --- | --- | --- |
| iOS | [`Qwen/Qwen2-0.5B-Instruct-MLX`](https://huggingface.co/Qwen/Qwen2-0.5B-Instruct-MLX/tree/759509dd0249685cbdeaa4e587679fabe366b918), `759509dd0249685cbdeaa4e587679fabe366b918` | `model.safetensors`, 278,064,766 bytes, SHA-256 `961b4727c18aec86456a213028e08f54cbe0081ad7b9e3e5eccfd967e47387dd` | 289,599,130 bytes |
| Android | [`Qwen/Qwen2-0.5B-Instruct-GGUF`](https://huggingface.co/Qwen/Qwen2-0.5B-Instruct-GGUF/tree/198f08841147e5196a6a69bd0053690fb1fd3857), `198f08841147e5196a6a69bd0053690fb1fd3857` | `qwen2-0_5b-instruct-q4_k_m.gguf`, 397,805,248 bytes, SHA-256 `f0a42bb979ca62b5e61f3bf924ab4b6a40aa091825ee7dcb4039949980ab81a8` | 397,816,592 bytes |

All listed files were downloaded and hashed. The two conversion repositories do not record the exact source-checkpoint commit used to create them, so this evidence establishes a matched official model family—not bit-identical conversion lineage.

Install a verified bundle atomically before connecting a device:

```bash
python3 tools/model_store.py ios .build/models
python3 tools/model_store.py android .build/models
```

## Reproduce the host and Android build

On the Linux orb, `.agents/setup` installs PHP 8.4, Composer 2.10.3, JDK 17, Android API/build-tools 36, NDK 27 and 29, CMake 3.22.1 and 3.31.6, and the small host utilities NativePHP needs. The verified run used PHP 8.4.25, Composer 2.10.3, OpenJDK 17.0.20.1, Android CLI 1.0.16261425, NDK 29 clang 20.0.0, and the NativePHP project's Gradle 8.14.5 wrapper. Then:

```bash
bash .agents/setup
bash probe/android/build-llama-aar.sh
bash probe/prepare-host.sh
cd probe/host
PATH=/opt/android-sdk/platform-tools:$PATH ANDROID_HOME=/opt/android-sdk php artisan native:run android --build=debug
```

The final command is the supported physical-device path: it prepares the bundle, compiles plugins, builds, installs, and starts the app. The generated host is intentionally ignored. `native:run` currently logs Gradle failures but can still exit zero, so inspect `nativephp/android-build.log` and require an APK plus `BUILD SUCCESSFUL`.

For the Linux static gate (where no device/ADB target is available), plugin compilation was invoked from the installed 4.5.1 compiler and `./gradlew assembleDebug` was run directly. The result was `BUILD SUCCESSFUL`; the APK is 115,566,474 bytes, signed with the debug v2 scheme, targets API 36, has `minSdkVersion: 29`, and contains the arm64 llama/ggml shared libraries. All arm64 AAR `LOAD` segments were checked at `0x4000` (16 KiB) alignment. This proves compilation and packaging, not API-29 runtime compatibility.

## Physical Android steps

1. Connect an arm64 Android phone with Developer options and USB debugging enabled. Record manufacturer/model, Android version, API level, RAM, and SoC. Confirm `adb devices` shows it as `device`.
2. Install the verified model and prepare the host as above.
3. Before building, leave `LOCAL_AI_MODEL_PATH` unset (the default is the app-private file `qwen2-0.5b-instruct`) or set another relative app-files name in `probe/host/.env`.
4. Run `export ANDROID_HOME="$HOME/Library/Android/sdk"`, then `PATH="$ANDROID_HOME/platform-tools:$PATH" php artisan native:run android --build=debug` on macOS, adjusting the SDK path if Android Studio uses another location.
5. Stage the GGUF into the installed debug app, then restart it:

   ```bash
   MODEL=.build/models/qwen2-0.5b-instruct/android/qwen2-0_5b-instruct-q4_k_m.gguf
   adb push "$MODEL" /data/local/tmp/qwen2-0.5b-instruct
   adb shell run-as dev.wojt.nativephplocalai.probe cp /data/local/tmp/qwen2-0.5b-instruct files/qwen2-0.5b-instruct
   adb shell rm /data/local/tmp/qwen2-0.5b-instruct
   adb shell am force-stop dev.wojt.nativephplocalai.probe
   adb shell monkey -p dev.wojt.nativephplocalai.probe 1
   ```

6. Turn on airplane mode and disable Wi-Fi before inference. Keep `adb logcat` running to capture crashes and unexpected network attempts.

## Mac/iPhone steps

1. On Apple silicon, record `xcodebuild -version` and `swift --version`. The selected Swift toolchain must parse MLX Swift 0.31.6's experimental Swift 6.3 manifest. Install PHP 8.4, Composer 2.10.3, CocoaPods, Android Studio/SDK, JDK 17, and Python 3. Do not run the Debian-only `.agents/setup` on macOS.
2. Set `ANDROID_HOME` to Android Studio's SDK, then run `bash probe/android/build-llama-aar.sh` and `bash probe/prepare-host.sh`. On macOS this generates both native projects and patches the generated Xcode project from semver requirements to the exact Swift package revisions above.
3. Run `python3 tools/model_store.py ios .build/models`.
4. Open `probe/host/nativephp/ios/NativePHP.xcodeproj`. Add `.build/models/qwen2-0.5b-instruct/ios` to the app target as a **folder reference** named `qwen2-0.5b-instruct`, with “Copy items if needed” enabled. The probe first checks app Documents, then the app bundle, and never falls back to a remote model.
5. Select the iPhone 16 Pro running iOS 27, choose the development team/signing identity, build, and run. Record the resolved package graph and preserve the full Xcode build log. Confirm the resolved commits exactly match the pin table before accepting the build.
6. Turn on airplane mode and disable Wi-Fi before inference. Capture Console logs and, if possible, an Instruments network trace showing no inference-time connections.

## Device acceptance matrix

Run only synthetic prompts. Capture terminal status, output, elapsed time, peak memory, relevant logs, and a screen recording showing the UI remains responsive.

- [ ] **Local load:** cold-start raw generation loads only the staged model; no network request or cloud fallback occurs.
- [ ] **Raw streaming:** “Stream raw text” emits ordered deltas and one `completed` terminal event.
- [ ] **Chat streaming:** “Stream templated chat” applies the model chat template, emits ordered deltas, and completes.
- [ ] **Decode cancellation:** start either short request, wait for visible deltas, press Cancel; receive one `cancelled` terminal event promptly, with no later deltas.
- [ ] **Prompt cancellation:** start “Long prefill cancellation probe” and press Cancel immediately, before the first delta; receive `cancelled`. Record latency. On iOS this is specifically a measured gate because the pinned MLX prefill path does not establish prompt-time cancellation responsiveness from source inspection alone.
- [ ] **Safe release/reuse:** after each cancellation, run Unload, start raw generation again, and complete successfully. Repeat load/unload at least 10 times while recording memory.
- [ ] **UI responsiveness:** controls and OS navigation remain responsive during model load, prefill, and decoding.
- [ ] **Queued lifecycle:** background the app, queue a request, verify it does not start, foreground, and verify it starts exactly once.
- [ ] **Active lifecycle:** background during active decode. Record whether that OS/backend allows it to continue; do not treat OS suspension as a plugin cancellation and do not require guaranteed background inference.
- [ ] **Activity recreation:** on Android, rotate or trigger “Don't keep activities”; return and verify no duplicate runtime registration, duplicate terminal event, or permanently paused queue.
- [ ] **Offline:** repeat local load, raw/chat, both cancellation phases, unload, and reuse in airplane mode while observing network tooling.
- [ ] **API 29 runtime:** if an API-29 arm64 device is available, run the same matrix. The successful API-29 build is not a substitute for this check.

## Current verdict

Android native integration, bridge generation, API-29 compilation, AAR packaging, Kotlin compatibility, and debug APK packaging are demonstrated in the orb. iOS source/API compatibility is pinned and audited but not compiled because the orb has no Swift/Xcode toolchain. Neither platform has run inference on a physical device in this work. Milestone 1 therefore remains **pending**, not passed.
