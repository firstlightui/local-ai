# Firstlight Local AI: approved design and staged handoff

Status: planning baseline, pending review of this consolidated document.
Date: 2026-09-19.
Target: NativePHP Mobile v4 only.
Repository: https://github.com/firstlightui/local-ai

This document records the decisions approved in the planning conversation. It
does not authorise implementation, publication, deployment or a package release.
The staged handoff below is milestone-level; executable implementation tasks must
be written after the research gates identify exact dependencies and interfaces.

## 1. Purpose and scope

Provide private, on-device inference through one Laravel-style PHP API:

- iOS/iPadOS: MLX Swift and mlx-swift-lm.
- Android: llama.cpp through Kotlin and JNI, with a CPU baseline.
- No inference server, Python runtime, cloud fallback or inference-time network
  access. Explicit model downloads may use the network.
- Backend selection belongs inside the plugin, not application platform checks.
- Applications address logical models, whose native artefacts may differ.

The Composer name is `firstlightui/local-ai`, following the other Firstlight UI
packages: PHP namespace `FirstlightUI\LocalAI`, facade `LocalAI` and Android
package `dev.firstlightui.plugins.local_ai`.

### Release scope

| Release | Scope |
| --- | --- |
| v0.1 | Plugin scaffolding; both native backends; registry; download/delete; load/unload; text and chat; streaming; cancellation; statistics; basic capability discovery and safety checks |
| v0.2 | JSON Schema structured output; improved compatibility checks; download resumption; richer device information |
| v0.3 | Embeddings and vision-language input |
| Later | Explicit background inference; background downloads; image/speech/audio generation; additional backends; tools/function calling; LoRA; conversion tooling; speculative decoding |

Future tasks are architectural extension points, not promised APIs or dependencies
in v0.1. Do not publish unimplemented media methods.

Non-goals: HTTP serving, Python interoperability, training/fine-tuning, on-phone
conversion, RAG/vector storage, agents, cloud inference or a shipped chat UI.
A future Laravel AI provider sits above this package; do not recreate that SDK.

## 2. Public API and execution contract

Long-running operations return handles immediately after native acceptance.
Discovery and status methods return synchronous snapshots.

```php
$model = LocalAI::model('qwen3.5:4b'); // Illustrative ID, not a verified default.
$download = $model->download();

// In a later handler, after installation has completed:
$generation = $model->stream('Explain multiple myeloma in one paragraph.');

$generation->id;
$generation->status();
$generation->cancel();

// Once completed:
$result = $generation->result();
```

| Methods | Contract |
| --- | --- |
| `models()` | Locally installed models |
| `availableModels()` | Configured registry definitions, not internet-wide search |
| `model($id)` | Logical model handle |
| `registerModel($definition)` | Application-provided definition; no implicit download |
| `installed()`, `size()`, `capabilities()`, `compatibility()`, `device()` | Current snapshots; estimates must be labelled as such |
| `path()` | Diagnostic path, not required for ordinary inference |
| `download()`, `load()`, `unload()`, `delete()` | Asynchronous operation handles |
| `generate()`, `chat()`, `stream()` | Asynchronous generation handles |
| `cancel()` / `LocalAI::cancel($id)` | Cancellation request, not confirmation of terminal cancellation |
| `result()` | Completed typed result, or defined not-ready/failed/cancelled/unavailable error |

`generate()` and `stream()` use the same native generation pipeline. Streaming
additionally emits text deltas. Chat accepts role/content messages and supports
streaming explicitly, for example `chat($messages, stream: true)`. Native code
applies the model's chat template; raw prompt generation must remain distinct.

PHP handles identify native operations and can be reconstructed in a later PHP
interaction. The exact reconstruction method will be fixed in the protocol/API
specification. NativePHP v4 keeps PHP/Laravel warm; native ownership is needed for
nonblocking execution and lifecycle independence, not because PHP always reboots.

### Readiness and options

- No implicit download in v0.1. Wait for installation completion before submitting
  generation. The original consecutive `download(); stream();` example is not a
  dependency chain.
- Automatic loading is supported. An accepted request loads its model when it
  reaches the front of the inference queue.
- Capture model revision and effective options at acceptance.
- Reject explicitly supplied unsupported options; never silently ignore them.
- Discover supported options and limits. A seed does not promise identical
  outputs across engines, quantisations or platforms.
- Generation statistics distinguish token usage from event counts. Exact timing
  fields and denominators must be specified before implementation; queue and
  loading time must not be silently counted as decoding throughput.

### Extension to other tasks

Use the same handle lifecycle, but explicit methods and task-specific results.
Image input producing text can extend text generation; generating an image is a
different task. Illustrative future names include `generateImage()`,
`synthesizeSpeech()`, `transcribe()`, `generateAudio()` and `embed()`.

| Task | Result | Incremental output where supported |
| --- | --- | --- |
| Text/chat | Text and token statistics | Decoded text deltas |
| Image generation | App-managed files and metadata | Progress/preview references |
| Speech/audio generation | App-managed files and metadata | Audio chunks |
| Transcription | Text and optional timestamped segments | Transcript segments |
| Embeddings | Vectors, exposed as PHP arrays | Usually final only |

Do not transfer large media as base64 through the bridge. Future media results
use native-managed asset references. Capability discovery distinguishes task,
input type and streaming support; image input is not image generation support.

Structured output will use standard JSON Schema, native constraints where
supported and automatic decoding. Reject unsupported schema features rather than
falling back silently to prompting for JSON. Exact schema subset and validation
semantics belong to the v0.2 design.

## 3. Ownership and concurrency

```text
PHP facade / model and operation handles
                 |
        Versioned bridge protocol
                 |
Native process-scoped runtime on each platform
  operations / queue / storage / lifecycle / event routing
                 |
        Native backend interface
           /                \
      MLX Swift        Kotlin / JNI / llama.cpp
```

Swift and Kotlin implement the same protocol and observable behaviour. Do not
introduce a shared cross-language runtime merely to avoid duplicate native code.
PHP has one bridge client, not separate PHP MLX/llama.cpp implementations.

- One process-wide inference queue, one active generation and one resident
  generative model by default. This is not a separate queue per logical model.
- FIFO queue with a bounded pending capacity; overflow rejects with `DeviceBusy`.
- Downloads execute separately, with bounded resource use.
- Automatic model switching happens between requests. With `auto_unload` enabled,
  release the resident model before loading another. Never unload resources still
  used by inference.
- Delete/unload reject conflicting active or queued inference for the target
  model rather than silently cancelling it.
- Queued cancellation can complete immediately. Running cancellation is
  cooperative; resources must no longer be in use before terminal cancellation.
- Completion may win a cancellation race. Each operation has one terminal state.
- Loading is a phase of an active generation, not a nested competing queue entry.

```text
queued -> running -> completed | failed | cancelled
```

Explicit load requests, model switching with `auto_unload=false`, concurrent
installation/deletion and duplicate submissions need exact arbitration rules in
the protocol milestone. They must preserve the ownership rules above.

## 4. Lifecycle policy

The initial proposal to cancel all work on backgrounding was superseded.

| Event | Approved behaviour |
| --- | --- |
| Brief loss of focus / inactive UI | No automatic cancellation |
| Background entry | Stop starting queued generations |
| Active inference while backgrounded | Continue only while OS and backend permit; otherwise interrupt safely with an explicit reason |
| Foreground return | Reconcile operation state and resume queued work |
| Activity recreation | Reattach event delivery; do not tie runtime ownership to the destroyed activity |
| Process termination | Operations/results are lost; installed models survive |
| Old handle after process restart | `OperationUnavailable`; never imply the operation is still running |

Queued requests remain in memory while the process survives. Do not promise
background completion or token-level inference resumption. The exact MLX
interruption point needs physical-device evidence.

Explicit background inference is a later-version requirement to investigate,
including Android foreground services and permitted iOS mechanisms. No dedicated
background inference service or unrelated background entitlement in v0.1.

Memory-pressure handling must release idle models where needed and coordinate
interruption before releasing active resources. Compatibility estimates cannot
guarantee immunity from OS process termination.

## 5. Registry and atomic installation

Use bundled definitions plus application-defined overrides. No remote registry
service is required. New model definitions do not require a package release,
but a new architecture may still require a backend update.

A definition maps a logical ID to platform artefact bundles containing:

- Backend and declared tasks/capabilities.
- Immutable source revision (Hugging Face commit rather than moving branch).
- Explicit files: safe relative destination, HTTPS URL, byte size and SHA-256.
- Licence information and advisory memory requirements.

For direct URLs, the checksum pins the expected content even when the URL is
mutable. Checksums detect corruption or unexpected content; they do not establish
trust when the manifest itself is untrusted. Applications own licence/provenance
review and authority to distribute their models.

MLX bundles include all required weights, configuration and tokenizer files.
GGUF bundles include companion files when required by the supported task.
Reject unsafe paths and prevent writes outside model storage. Do not execute
remote repository code.

Installation sequence:

1. Resolve the definition and check storage, including staging/replacement needs.
2. Download into private staging storage.
3. Verify every required file's size and digest.
4. Atomically publish the complete installation.

Only step 4 makes a new installation visible as installed. Failed/cancelled work
must not appear complete. Inference loads local installed files only and must not
automatically repair missing files by contacting a model host.

Record the resolved definition/revision with each installation. Registry changes
do not silently change installed models. An explicit replacement download keeps
the old installation usable until successful publication. Prohibit replacement
while that model has queued or active inference; define reservation/commit races
in the installation milestone.

Storage is app-managed rather than bundled by default. Bundled/local import is a
separate follow-up design, not permission to bypass download integrity rules.
Private repository authentication and background downloads are deferred.
Resumption is v0.2; v0.1 still needs cancellation, progress, storage checks and
safe cleanup/recovery of incomplete staging directories.

Effective capabilities are the intersection of registered model declarations
and actual backend support. A registry entry alone never proves compatibility.

## 6. Streaming, recovery and retention

- PHP creates the operation UUID before submission; responses/events carry it.
- `TextDeltaGenerated` replaces the proposed `TokenGenerated` name.
- Deltas contain `operationId`, increasing sequence number and decoded `delta`.
- Batch small chunks to avoid overwhelming the bridge; event count is not token
  count. Correctly handle UTF-8 fragments and tokenizer-specific decoding.
- Do not send full accumulated output with every delta. A native snapshot provides
  accumulated text and the corresponding sequence position for reconciliation.
- Native state is authoritative. Events are not a durable queue or an exactly-once
  delivery mechanism. Reconcile after navigation, missed events or backgrounding.
- Completed, failed and cancelled are distinct terminal outcomes. Partial text may
  be inspected after interruption but is never a completed successful answer.
- Retain results in memory under configurable time and total-byte limits. Never
  evict active operations. Enforce active input/output limits rather than relying
  only on completed-result eviction to bound memory.
- Expired results and lost native sessions report `OperationUnavailable`.
- Do not persist prompts or results by default. Applications explicitly save them
  under their own policies.

Protocol fixtures must specify snapshot/delta race handling, terminal-event
ordering, retention expiry, submission acknowledgement and UUID reuse. A lost
acknowledgement must not lead to blindly submitting duplicate inference.

## 7. Errors and privacy

| Situation | Behaviour |
| --- | --- |
| Invalid options, unknown/uninstalled model, unsupported feature, queue full | Reject submission with typed exception; no accepted operation |
| Native load/inference failure after acceptance | Failed handle and `OperationFailed` notification |
| `result()` before completion | `OperationNotReady` |
| `result()` after failure | Corresponding typed exception |
| Cancelled generation | `GenerationCancelled`; partial output separately accessible |
| Expired operation or process restarted | `OperationUnavailable` |

Validate in PHP for useful feedback and natively for authoritative state and
safety. Conditions changing after acceptance become operation failures.

Errors include stable backend-neutral codes, safe messages, operation IDs when
applicable and limited structured details. Cover model lookup/installation,
download, checksum, load, storage, memory, unsupported model/capability,
cancellation, generation and busy conditions. Do not expose raw native dumps,
prompts, outputs or credentials in default errors/logs.

No inference content leaves the device through this plugin. No telemetry by
default. This is a package behaviour guarantee, not a claim that the host
application or operating system cannot perform its own networking or logging.

## 8. Native packaging and evidence status

Use NativePHP's plugin manifest and conventional `resources/ios/Sources` and
`resources/android/src` layout, plus `src/` for PHP, tests and protocol fixtures.
Exact native build-library placement follows the packaging feasibility result.

### Established from documentation/source research

- NativePHP v4 has persistent PHP execution; long inference must not block its
  UI event loop. Android activity recreation can reattach to a warm runtime.
- The plugin manifest supports Swift Package and Gradle dependencies, startup
  initialisation, platform minimums and declared events.
- NativePHP documentation states iOS 18 / Android API 29 baseline minimums.
- MLX upstream offers text/chat products, local-directory loading and streamed
  generation; tokenizer integration also needs an offline audit.
- llama.cpp has an Android library/JNI/CMake reference and CPU execution path.
- `MLXEmbedders`, `MLXGuidedGeneration`, and `MLXHuggingFace` exist in current
  mlx-swift-lm source; they are not needed for initial text/chat integration.

### Not established and not to be treated as final pins

Research reported release candidates `mlx-swift-lm 3.31.4`, `mlx-swift 0.31.6`
and llama.cpp `b11056`. Some inspected APIs came from newer development branches.
These identifiers are research leads, not an approved compatible dependency set.
Re-read exact release manifests/APIs and lock compatible immutable revisions
before writing native implementation instructions.

- Confirm Swift/Xcode requirements, MLX products, Metal resources and device
  support against the chosen release, not `main`.
- Verify v4 event delivery against source: the events documentation still
  describes WebView injection while v4 architecture describes native rendering.
- Prefer an Android library/AAR boundary for JNI integration; choose prebuilt AAR
  distribution versus app-time CMake build after testing NativePHP integration.
- The upstream Android sample uses API 33. This is not proof of a fundamental
  llama.cpp minimum. API 29 support needs its own build/device evidence.
- Audit CPU feature dispatch, packaged native libraries, ABI and Android page-size
  compatibility. CPU inference must work without Vulkan; acceleration is optional.
- Cancellation must cover prompt processing as well as token decoding; a sample's
  coroutine cancellation alone is not proof native work has stopped.

### Baseline model

Use a small Qwen 0.5B Instruct model as the proposed integration fixture. Qwen2
architecture support was found in both engines. Research identified Qwen2 and
Qwen2.5 distributions, but did not establish a complete matched MLX/GGUF manifest
pair with verified hashes and licence review.

Select the same base checkpoint family for both platform artefacts. Do not mix
Qwen2 and Qwen2.5 under one logical model. Qwen 3.5 4B remains an additional
compatibility/performance candidate, not the default or first feasibility gate.
An integration fixture is not a clinical suitability recommendation.

### Reference sources

- [NativePHP threading](https://nativephp.com/docs/mobile/4/architecture/threading-model)
- [NativePHP plugin structure](https://nativephp.com/docs/mobile/4/plugins/creating-plugins)
- [NativePHP dependencies](https://nativephp.com/docs/mobile/4/plugins/permissions-dependencies)
- [NativePHP advanced configuration](https://nativephp.com/docs/mobile/4/plugins/advanced-configuration)
- [NativePHP events](https://nativephp.com/docs/mobile/4/plugins/events)
- [MLX Swift releases](https://github.com/ml-explore/mlx-swift/releases)
- [MLX Swift LM releases](https://github.com/ml-explore/mlx-swift-lm/releases)
- [llama.cpp Android reference](https://github.com/ggml-org/llama.cpp/tree/master/examples/llama.android)
- [Qwen2 official GGUF candidate](https://huggingface.co/Qwen/Qwen2-0.5B-Instruct-GGUF)
- [Qwen2.5 MLX candidate](https://huggingface.co/mlx-community/Qwen2.5-0.5B-Instruct-4bit)

These are research entry points, including mutable pages. The feasibility report
must replace implementation references with exact revision/file citations.

## 9. Verification and available hardware

User-confirmed: Mac/Xcode access and iPhone 16 Pro running iOS 27.
Android physical-device access is available, but model and OS remain unselected.
An ARM64 phone with 8 GB RAM is a recommendation, not a confirmed requirement.

Distinguish build compatibility, tested device/OS combinations and acceptable
performance across a supported range. One iPhone test does not certify older
devices. Android emulator/build success does not satisfy device inference gates.

Use a minimal development host with download, generate, cancel and status
controls. It is a test harness, not a shipped chat UI. Use synthetic prompts;
do not require real clinical information for development or evidence capture.

Tests must cover:

- Shared protocol fixtures in PHP, Swift and Kotlin, including negative cases.
- Ordered/duplicate/missing deltas and snapshot recovery without duplicate text.
- Cancellation while queued, loading, processing prompts and decoding; completion
  races and subsequent generation after cancellation.
- Cross-model queue order, switching, conflicting unload/delete and queue overflow.
- Corrupt/truncated downloads, unsafe paths, insufficient storage and interrupted
  replacement without loss of a valid previous installation.
- Unicode decoding, chat templates, output limits and truthful statistics.
- Foreground/background transitions, activity recreation, process restart,
  retention expiry and repeated load/unload memory behaviour.
- Offline inference after installation and after an app restart.
- Instrumented network checks plus loader-path inspection: airplane mode alone
  establishes offline functionality, not absence of network attempts.

Do not assert identical generated text across platforms. Verify protocol and task
behaviour, coherent output and correct state/resource handling. PHP mocks cannot
prove native inference, cancellation latency or memory/thermal behaviour.

## 10. Staged build handoff

Implementation remains for later workers. Each task must include fixed inputs,
owned files, dependencies, authoritative API references, commands and required
evidence. Do not let workers independently invent incompatible bridge contracts.

### Milestone 1: native feasibility

Resolve exact NativePHP/MLX/llama.cpp versions, native packaging and a matched
small-model pair. Build a minimal v4 host on both platforms. Demonstrate local
loading, incremental output, chat templating and cancellation without UI blocking.
Record physical-device environment and lifecycle observations. Temporary probe
interfaces are not the published API.

Exit evidence: reproducible build instructions, locked source versions, complete
model manifests, device records and explicit pass/fail observations. Stop and
revise the design if offline loading, packaging or resource ownership fails.

### Milestone 2: shared protocol and PHP API

Freeze wire schemas, method signatures, states, error codes, event payloads,
capability/option representation and shared fixtures. Resolve numerical limits,
retention policy, statistics definitions, acknowledgement/idempotency and mutation
arbitration. Implement the PHP API against those contracts in the later build.

Exit evidence: fixtures covering acceptance/rejection, handles, state transitions,
failures and snapshot recovery; no native-worker-specific protocol invention.

### Milestone 3: model installation

Implement both platform download/storage paths against strict manifests, including
verification, atomic publication, cancellation, progress, deletion and staging
recovery. Preserve valid installed revisions across failed replacements.

Exit evidence: native storage/download tests and real installation on both phones.

### Milestone 4: runtime integration

Connect native backends to the agreed protocol and process-scoped runtime.
Exercise queueing, model switching, cancellation, retention, lifecycle recovery,
activity recreation and safe memory release.

Exit evidence: shared fixture conformance plus physical-device lifecycle/race tests.

### Milestone 5: release acceptance

The same PHP test application downloads and streams the approved logical model on
both phones without platform branches. After restart, inference succeeds offline.
Record network/privacy checks, limitations and tested device versions.

Exit evidence: both platform runs, distribution/build reproducibility and a clear
supported/tested matrix. Publication requires separate authorisation.

### Work allocation after the gates

The two native feasibility tracks can run independently. After protocol freeze,
PHP, iOS and Android tasks can have disjoint ownership while sharing fixtures.
Keep one owner responsible for protocol changes and combined integration. Do not
mark work complete solely on an agent's report or a compiling mock backend.

## 11. Decisions still needed before executable tasks

These are explicit planning gates, not permission for a worker to guess:

1. Package licence.
2. Exact NativePHP release, native dependency revisions and toolchain matrix.
3. Android native-library distribution strategy and supported ABIs/API floor.
4. Matched baseline artefacts, hashes, licence/provenance and complete file lists.
5. Protocol details listed in milestones 2–3, including safe default limits and
   behaviour when automatic loading/unloading is disabled.
6. Physical Android model/OS and measured lifecycle/cancellation behaviour.

The approved behaviour in this document remains the baseline. Any feasibility
finding requiring a public-contract change returns to design review before build.
