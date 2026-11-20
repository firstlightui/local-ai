# Firstlight Local AI

Local AI generates text with the language model already on the phone: Apple Foundation Models on iOS and Gemini Nano on Android. It doesn't download a model or run its own inference engine, and it never sends a prompt or its output off the device. On a device without a supported model it tells you why, so your app can offer something else.

- **Prompts and chats:** generate from one prompt with optional instructions, or continue a conversation, with a temperature and a token limit.
- **Streamed output:** each chunk of text arrives as an event, then one completed, failed or cancelled event. Cancel a generation at any time.
- **Availability with a reason:** available, downloadable, downloading or unavailable, and why, such as Apple Intelligence being turned off.
- **Survives navigation:** each generation has an ID, so a screen can pick up its text again after the user navigates away and back.
- **PHP and JavaScript:** a PHP facade for native screens and Livewire, and a JavaScript library for Inertia with Vue or React.

Composer package `firstlightui/local-ai`, PHP namespace `FirstlightUI\LocalAI`, Kotlin package `dev.firstlightui.plugins.local_ai`. Free and MIT licensed. Part of [Firstlight UI](https://firstlightui.dev/plugins).

## Requirements

| | iOS | Android |
| --- | --- | --- |
| Model | [Apple Foundation Models](https://developer.apple.com/documentation/foundationmodels) (`SystemLanguageModel.default`) | Gemini Nano through the [ML Kit GenAI Prompt API](https://developers.google.com/ml-kit/genai/prompt/android) (`com.google.mlkit:genai-prompt:1.0.0-beta5`) |
| Generates on | iOS 26 or later, on a [device that supports Apple Intelligence](https://www.apple.com/apple-intelligence/), with Apple Intelligence turned on | A [device on Google's Prompt API list](https://developers.google.com/ml-kit/genai), such as a Pixel 9 or later or a Galaxy S25 or later, with a locked bootloader |
| Installs on | iOS 18 or later | Android 10 (API 29) or later |

The app installs and runs on every version in the last row. Where the model can't run, `availability()` reports `unavailable` with a reason, and generating throws `unavailable`.

It also needs PHP 8.3 or later and `nativephp/mobile` ^4.6.1. It is tested with `nativephp/mobile` 4.8 and works alongside [Firstlight UI](https://github.com/firstlightui/nativephp) 1.1. It needs no permissions, API keys or environment variables.

Google offers the Prompt API as a beta with no SLA or deprecation policy, so a future ML Kit release may need a new version of this plugin.

## Installation

```bash
composer require firstlightui/local-ai
php artisan native:plugin:register firstlightui/local-ai
```

NativePHP compiles only the plugins the app lists. `native:plugin:register` adds `FirstlightUI\LocalAI\LocalAIServiceProvider` to `NativeServiceProvider::plugins()`.

For the JavaScript library, map an import in your app's `package.json`, next to NativePHP's own:

```json
"imports": {
    "#nativephp": "./vendor/nativephp/mobile/resources/dist/native.js",
    "#local-ai": "./vendor/firstlightui/local-ai/resources/js/localAI.js"
}
```

## Usage (PHP)

```php
use FirstlightUI\LocalAI\Facades\LocalAI;

$availability = LocalAI::availability();

if ($availability->status === 'downloadable') {
    LocalAI::download(); // Android only; progress arrives as ModelDownloadProgress events
}

if ($availability->isAvailable()) {
    $generation = LocalAI::generate('Name three moons of Jupiter.', instructions: 'Answer in one line.');
}
```

Continue a conversation with `chat()`. System messages may only come first, and the last message must come from the user:

```php
$generation = LocalAI::chat([
    ['role' => 'system', 'content' => 'Be brief.'],
    ['role' => 'user', 'content' => 'Hi'],
    ['role' => 'assistant', 'content' => 'Hello.'],
    ['role' => 'user', 'content' => 'Name a colour.'],
], ['temperature' => 0.7, 'maxTokens' => 128]);
```

Both return a `Generation` straight away, while the text streams in the background:

```php
$generation->id;         // keep it to pick the generation up later: LocalAI::generation($id)
$generation->status();   // queued, running, completed, failed or cancelled
$generation->text();     // the text so far, including partial output
$generation->cancel();   // a request; the terminal event confirms it
$generation->result();   // the completed text, or a LocalAIException (not_ready, cancelled, or the failure's code)
$generation->snapshot(); // id, status, text, sequence, errorCode and errorMessage in one call
```

The only options are `temperature` (0 to 2) and `maxTokens` (a positive integer). Any other option, an unknown role or an empty message throws `InvalidArgumentException` before any native call.

### Listening for events

On a native (EDGE or Firstlight) screen, listen with `#[On]`. Parameters bind to the payload by name:

```php
use FirstlightUI\LocalAI\Events\GenerationCompleted;
use FirstlightUI\LocalAI\Events\GenerationFailed;
use FirstlightUI\LocalAI\Events\TextDeltaGenerated;
use FirstlightUI\LocalAI\Facades\LocalAI;
use Native\Mobile\Attributes\On;

class AskScreen extends NativeComponent
{
    public ?string $id = null;

    public string $answer = '';

    public function ask(): void
    {
        $this->answer = '';
        $this->id = LocalAI::generate('Suggest a name for a cat.')->id;
    }

    #[On(TextDeltaGenerated::class)]
    public function delta(string $id, int $sequence, string $delta): void
    {
        if ($id === $this->id) {
            $this->answer .= $delta;
        }
    }

    #[On(GenerationCompleted::class)]
    public function completed(string $id, string $text): void
    {
        if ($id === $this->id) {
            $this->answer = $text; // the authoritative text
        }
    }

    #[On(GenerationFailed::class)]
    public function failed(string $id, string $code, string $message): void
    {
        // $code is one of the error codes below
    }

    public function onResume(): void
    {
        // Events reach only the live screen. Catch up after the user comes back.
        if ($this->id !== null) {
            $this->answer = LocalAI::generation($this->id)->text();
        }
    }
}
```

In a Livewire component in a web view, use `#[OnNative(TextDeltaGenerated::class)]` from `Native\Mobile\Attributes\OnNative`, with the payload as an array.

## Usage (JavaScript)

```js
import { Availability, Download, Generate, Cancel, Status, Events, LocalAIError } from '#local-ai';
import { On, Off } from '#nativephp';

const availability = await Availability(); // { status, reason, platform }

let answer = '';
const { id } = await Generate({
    messages: [
        { role: 'system', content: 'Be brief.' },
        { role: 'user', content: 'Suggest a name for a cat.' },
    ],
    temperature: 0.7,
    maxTokens: 64,
});

On(Events.TextDeltaGenerated, (event) => {
    if (event.id === id) answer += event.delta;
});
On(Events.GenerationCompleted, (event) => {
    if (event.id === id) answer = event.text;
});

await Cancel(id);
const snapshot = await Status(id); // { id, status, text, sequence, errorCode, errorMessage }
```

There is one named export per bridge function: `Availability`, `Download`, `Generate`, `Cancel` and `Status`. `Generate` creates the ID for you unless you pass one. It validates messages and options the way PHP does and throws a `TypeError` before any native call. A native failure rejects with a `LocalAIError` whose `code` is one of the error codes below.

## API

### Facade methods

| Method | Bridge function | What it does |
| --- | --- | --- |
| `availability(): Availability` | `LocalAI.Availability` | Whether the on-device model can be used now. `status` is `available`, `downloadable`, `downloading` or `unavailable`; `reason` says why; `platform` is `ios` or `android`. |
| `download(): Availability` | `LocalAI.Download` | On Android, asks the system to download Gemini Nano when it is `downloadable`, and reports `downloading`. iOS manages Apple Intelligence assets itself, so this only reports availability. |
| `generate(string $prompt, ?string $instructions = null, array $options = []): Generation` | `LocalAI.Generate` | Queues a generation from one prompt. |
| `chat(array $messages, array $options = []): Generation` | `LocalAI.Generate` | Queues a generation that continues a conversation. |
| `generation(string $id): Generation` | | A handle for a generation started earlier, for example in another request. |
| `cancel(string $id): void` | `LocalAI.Cancel` | Requests cancellation. |
| `snapshot(string $id): array` | `LocalAI.Status` | The native state of a generation. |

### Availability reasons

| `reason` | Platform | Meaning |
| --- | --- | --- |
| `os_unsupported` | iOS | Earlier than iOS 26 |
| `device_not_eligible` | both | The device can't run the model |
| `not_enabled` | iOS | Apple Intelligence is turned off |
| `model_not_ready` | iOS | Reported with `downloading`: the system is still preparing the model |
| `status_check_failed` | Android | AICore didn't answer within 5 seconds |
| `unknown` | iOS | A reason added in a later iOS version |

### Events

All events are in `FirstlightUI\LocalAI\Events`.

| Event | Payload | When |
| --- | --- | --- |
| `TextDeltaGenerated` | `id`, `sequence`, `delta` | New text. `sequence` counts up from 1 for each generation. An event is not a token. |
| `GenerationCompleted` | `id`, `text` | The generation finished. `text` is the whole response. |
| `GenerationFailed` | `id`, `code`, `message`, `text` | The generation failed after it was accepted. `text` holds any partial output. |
| `GenerationCancelled` | `id`, `text` | The generation was cancelled. `text` holds any partial output. |
| `ModelDownloadProgress` | `status`, `bytesDownloaded`, `bytesToDownload`, `message` | Android download progress. `status` is `started`, `downloading`, `completed` or `failed`. |

Events are notifications, not a durable queue: they reach the screen that is live when they arrive. After navigation or backgrounding, reconcile with `$generation->snapshot()`, which returns the authoritative text and the `sequence` of the last delta included in it.

### Error codes

`LocalAIException::$errorCode`, `GenerationFailed::$code` and `LocalAIError.code` use these codes:

| Code | Meaning |
| --- | --- |
| `unavailable` | The model can't be used on this device now. Check `availability()`. |
| `busy` | 8 generations are already waiting. |
| `duplicate_id` | A generation with this ID exists. |
| `invalid_request` | The native side rejected the request. |
| `unknown_operation` | No generation has this ID: it expired, or the app restarted. |
| `not_ready` | `result()` was called before the generation finished. |
| `cancelled` | `result()` was called on a cancelled generation. |
| `context_exceeded` | The prompt and history don't fit the model's context window. |
| `guardrail` | The system's safety checks blocked the prompt or the response. |
| `unsupported_language` | iOS: the model doesn't support the language or locale. |
| `unsupported` | Android: the device's model doesn't support system instructions. |
| `rate_limited` | The system is busy or the app's quota is used up. Retry later. |
| `background_blocked` | Android blocked generation because the app wasn't in the foreground. |
| `generation_failed` | Any other failure. The message carries the system's reason. |
| `bridge_failed` | The native call returned nothing, usually because the plugin isn't registered or the app isn't a native build. |

## Runtime behaviour

- Each app process has one native first-in, first-out queue that runs one generation at a time. Up to 8 generations can wait behind it; another submission throws `busy`.
- Queued generations don't start while the app is in the background, because Android blocks background use of Gemini Nano. The queue resumes when the app returns to the foreground.
- The runtime keeps the last 32 finished generations in memory. An older ID, or any ID after the app restarts, throws `unknown_operation`.
- Gemini Nano takes input of up to about 4,000 tokens, and AICore enforces a per-app quota that it reports as `rate_limited`. Apple's on-device model has a similar context window, reported as `context_exceeded`.
- On Android, system instructions need a model with the system prompt feature; if it lacks one, the generation fails with `unsupported`.

## Testing on simulators and emulators

The iOS Simulator uses the Mac's own Apple Intelligence model, so generation in an iOS 26 simulator needs a Mac with Apple Intelligence turned on. On a Mac without the model, `availability()` can still report `available` while generation fails with `generation_failed`. Android emulators have no AICore, so they report `unavailable`; test Android generation on a supported device.

## Build notes

- **Android Kotlin.** ML Kit GenAI ships Kotlin 2.4 metadata, which NativePHP's Kotlin 2.0 template can't read. The plugin's `pre_compile` hook (`nativephp:local-ai:prepare-android`) runs before every Android build. It raises the host's `kotlin` version in `gradle/libs.versions.toml` to 2.3.21, and replaces the `kotlinOptions` block, which Kotlin 2.2 and later reject, with the equivalent `compilerOptions` DSL. The hook is idempotent and never lowers a newer version. The Compose compiler plugin follows the same version.
- **iOS linking.** NativePHP targets iOS 18.2, and Foundation Models first shipped in iOS 26. Every Foundation Models call sits behind `#available(iOS 26.0, *)`, so the linker weak-links the framework and the app launches on iOS 18 to 25.

## Development

```bash
composer install
vendor/bin/pest                                                        # PHP, with a fake bridge
(cd resources/android && ./gradlew test compileHostCheckKotlin)        # JDK 21 and the Android 36 platform
bin/typecheck-ios                                                      # needs Xcode
```

The Gradle build unit tests the Android request parser on the JVM and typechecks every Kotlin source against `android.jar` and the ML Kit classes the app links. `bin/typecheck-ios` typechecks the Swift sources against the iOS SDK at the iOS 18 deployment floor. CI runs all of these except the iOS typecheck.

## Support

Email [team@firstlightui.dev](mailto:team@firstlightui.dev) or open an issue at [github.com/firstlightui/local-ai/issues](https://github.com/firstlightui/local-ai/issues). Changes are listed in the [changelog](https://github.com/firstlightui/local-ai/blob/main/CHANGELOG.md).

## Releases

Tag `vX.Y.Z` on `main` and keep `version` in `nativephp.json` the same. Packagist picks up the tag.

## License

MIT. See [LICENSE](https://github.com/firstlightui/local-ai/blob/main/LICENSE).
