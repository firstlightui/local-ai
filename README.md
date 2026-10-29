# Firstlight Local AI

A NativePHP Mobile plugin that generates text with the operating system's
built-in on-device model. The plugin does not download models or run its own
inference engine, and it never sends prompts or output off the device.

| Platform | Model | Requirements |
| --- | --- | --- |
| iOS | Apple Foundation Models (`SystemLanguageModel.default`) | iOS 26+, Apple Intelligence-capable device with Apple Intelligence turned on |
| Android | Gemini Nano through ML Kit GenAI Prompt API (`com.google.mlkit:genai-prompt:1.0.0-beta5`) | AICore-supported device with a locked bootloader |

The app still installs on iOS 18 and Android API 29. On devices without a
supported model, `availability()` reports `unavailable` with a reason.

## Install

```bash
composer require firstlightui/local-ai
php artisan native:plugin:register firstlightui/local-ai
```

## Usage

```php
use FirstlightUI\LocalAI\Facades\LocalAI;

$availability = LocalAI::availability();
// status: available | downloadable | downloading | unavailable
// reason: device_not_eligible, not_enabled, model_not_ready, os_unsupported, …

if ($availability->status === 'downloadable') {
    LocalAI::download(); // Android only; progress arrives as ModelDownloadProgress
}

$generation = LocalAI::generate('Name three moons of Jupiter.', instructions: 'Answer in one line.');

$generation = LocalAI::chat([
    ['role' => 'system', 'content' => 'Be brief.'],
    ['role' => 'user', 'content' => 'Hi'],
    ['role' => 'assistant', 'content' => 'Hello.'],
    ['role' => 'user', 'content' => 'Name a colour.'],
], ['temperature' => 0.7, 'maxTokens' => 128]);

$generation->id;         // keep this to rebuild the handle later: LocalAI::generation($id)
$generation->status();   // queued | running | completed | failed | cancelled
$generation->text();     // text so far, including partial output
$generation->cancel();   // a request; the terminal event confirms it
$generation->result();   // completed text, or LocalAIException (not_ready, cancelled, …)
```

The only options are `temperature` (0–2) and `maxTokens`. Any other option is
rejected, not silently ignored.

### Events

| Event | Payload |
| --- | --- |
| `TextDeltaGenerated` | `id`, `sequence`, `delta` |
| `GenerationCompleted` | `id`, `text` |
| `GenerationFailed` | `id`, `code`, `message`, `text` (partial) |
| `GenerationCancelled` | `id`, `text` (partial) |
| `ModelDownloadProgress` | `status`, `bytesDownloaded`, `bytesToDownload`, `message` |

All events are in `FirstlightUI\LocalAI\Events`. They are notifications, not a
durable queue. After navigation or backgrounding, reconcile with
`$generation->snapshot()`, which returns the authoritative text and the
`sequence` of the last delta included in it.

## Runtime behaviour

- Each process has a single native FIFO queue that runs one generation at a
  time. Up to 8 generations can wait; another submission is rejected with
  `busy`.
- Queued work does not start while the app is in the background. Android
  blocks background use of Gemini Nano. When the app returns to the
  foreground, the queue resumes.
- The runtime keeps snapshots in memory for the last 32 finished generations.
  An expired or unknown ID throws `unknown_operation`, as does any ID after the
  app restarts.
- On Android, system instructions need `isSystemPromptAvailable()`. If the
  device's model lacks it, the generation fails with `unsupported`.

## Build notes

- **Android Kotlin.** Every ML Kit GenAI Prompt API release ships Kotlin
  2.2–2.4 metadata. NativePHP's Android template compiles with Kotlin 2.0.0,
  which cannot read it. The plugin's `pre_compile` hook
  (`nativephp:local-ai:prepare-android`) runs before every Android build. It
  raises the host's `kotlin` version in `gradle/libs.versions.toml` to 2.3.21
  and replaces the `kotlinOptions` block, which Kotlin 2.2+ rejects, with the
  equivalent `compilerOptions` DSL. The hook is idempotent, and it never lowers
  a version that is already newer.
- **iOS linking.** NativePHP targets iOS 18.2, and Foundation Models first
  shipped in iOS 26. Every Foundation Models call is behind `#available(iOS 26.0, *)`.
  The framework must be weak-linked for the app to launch on iOS 18–25. Check
  that on an older device before you rely on it.

## Development

```bash
composer install
vendor/bin/pest
```

PHP tests use a fake bridge. Native behaviour needs a supported physical
device, because neither simulators nor emulators provide these models.
