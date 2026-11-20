# Changelog

## Unreleased

### Fixed

- Android: generations no longer fail with `invalid_request`. NativePHP passes nested parameters as `JSONArray` and `JSONObject`, which the request parser didn't read, so every `Generate` call was rejected.
- Android: the queue no longer pauses while the app is in the foreground. Background state now comes from the process lifecycle, which reports the current state even when the plugin starts after the first activity.
- A failure the plugin doesn't map to a code (`generation_failed`) now carries the system's reason instead of "Generation failed."
- iOS: reading whether a failed generation was cancelled no longer races with `cancel()`.

### Added

- A JavaScript library in `resources/js/localAI.js` with one export per bridge function, event names and `LocalAIError`, for Inertia with Vue or React.
- Off-device checks: a Gradle build that unit tests the Android request parser and typechecks the Kotlin against the ML Kit classes, and `bin/typecheck-ios`.

### Changed

- Requires `nativephp/mobile` ^4.6.1, the first release with the `DispatchableFromNative` contract the events implement. On 4.6.0 the event classes failed to load.
- Native sources moved to `resources/ios/Sources` and `resources/android/src`, the layout the NativePHP Plugin Marketplace expects.
- Android: replaced the deprecated `isSystemPromptAvailable()` with `getAvailableFeatures()`, and declared `androidx.lifecycle:lifecycle-process`.
- README: JavaScript usage, `#[On]` listeners, the full API, availability reasons, error codes, supported devices and support contacts.

## 1.0.0-alpha1

- First preview: availability, download, generate, chat, streamed events, cancellation and snapshots, with Apple Foundation Models on iOS and Gemini Nano on Android.
