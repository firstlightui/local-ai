<?php

namespace FirstlightUI\LocalAI;

use Closure;
use InvalidArgumentException;

/**
 * Text generation with the operating system's built-in on-device model.
 *
 * iOS uses Apple Foundation Models (iOS 26+, Apple Intelligence devices).
 * Android uses Gemini Nano through ML Kit GenAI (AICore-capable devices).
 * Nothing is sent off the device by this plugin.
 */
final class LocalAI
{
    private const OPTIONS = ['temperature', 'maxTokens'];

    private const ROLES = ['system', 'user', 'assistant'];

    /** @var Closure(string, string): (string|false|null) */
    private Closure $transport;

    public function __construct(?Closure $transport = null)
    {
        $this->transport = $transport ?? static function (string $function, string $parameters) {
            if (! function_exists('nativephp_call')) {
                throw new LocalAIException('unavailable', 'Local AI requires the NativePHP Mobile runtime.');
            }

            return nativephp_call($function, $parameters);
        };
    }

    /**
     * Current availability of the on-device model.
     */
    public function availability(): Availability
    {
        return Availability::fromArray($this->call('LocalAI.Availability'));
    }

    /**
     * Ask the OS to fetch its model when it reports `downloadable`.
     *
     * Android reports progress through ModelDownloadProgress events. iOS
     * downloads Apple Intelligence assets itself, so this only reports status.
     */
    public function download(): Availability
    {
        return Availability::fromArray($this->call('LocalAI.Download'));
    }

    /**
     * Generate a response to a single prompt.
     *
     * @param  array{temperature?: float|int, maxTokens?: int}  $options
     */
    public function generate(string $prompt, ?string $instructions = null, array $options = []): Generation
    {
        $messages = [['role' => 'user', 'content' => $prompt]];

        if ($instructions !== null) {
            array_unshift($messages, ['role' => 'system', 'content' => $instructions]);
        }

        return $this->chat($messages, $options);
    }

    /**
     * Continue a conversation. Messages use the roles system, user and
     * assistant; system messages may only appear first and the last message
     * must come from the user.
     *
     * @param  list<array{role: string, content: string}>  $messages
     * @param  array{temperature?: float|int, maxTokens?: int}  $options
     */
    public function chat(array $messages, array $options = []): Generation
    {
        $bytes = random_bytes(16);
        $bytes[6] = chr(ord($bytes[6]) & 0x0F | 0x40);
        $bytes[8] = chr(ord($bytes[8]) & 0x3F | 0x80);
        $id = vsprintf('%s%s-%s-%s-%s-%s%s%s', str_split(bin2hex($bytes), 4));

        $this->call('LocalAI.Generate', [
            'id' => $id,
            ...$this->validateMessages($messages),
            ...$this->validateOptions($options),
        ]);

        return new Generation($id, $this);
    }

    /**
     * Rebuild a handle for an operation started in an earlier request.
     */
    public function generation(string $id): Generation
    {
        return new Generation($id, $this);
    }

    /**
     * Request cancellation. The terminal state arrives as GenerationCancelled,
     * or as GenerationCompleted if completion won the race.
     */
    public function cancel(string $id): void
    {
        $this->call('LocalAI.Cancel', ['id' => $id]);
    }

    /**
     * @return array{id: string, status: string, text: string, sequence: int, errorCode: string, errorMessage: string}
     */
    public function snapshot(string $id): array
    {
        $snapshot = $this->call('LocalAI.Status', ['id' => $id]);

        return [
            'id' => (string) ($snapshot['id'] ?? $id),
            'status' => (string) ($snapshot['status'] ?? ''),
            'text' => (string) ($snapshot['text'] ?? ''),
            'sequence' => (int) ($snapshot['sequence'] ?? 0),
            'errorCode' => (string) ($snapshot['errorCode'] ?? ''),
            'errorMessage' => (string) ($snapshot['errorMessage'] ?? ''),
        ];
    }

    /**
     * @return array{instructions: string, messages: list<array{role: string, content: string}>}
     */
    private function validateMessages(array $messages): array
    {
        if (! array_is_list($messages) || $messages === []) {
            throw new InvalidArgumentException('Messages must be a non-empty list.');
        }

        $instructions = [];
        $conversation = [];

        foreach ($messages as $index => $message) {
            $role = $message['role'] ?? null;
            $content = $message['content'] ?? null;

            if (! in_array($role, self::ROLES, true)) {
                throw new InvalidArgumentException("Message {$index} has an unsupported role.");
            }

            if (! is_string($content) || trim($content) === '') {
                throw new InvalidArgumentException("Message {$index} must have non-empty string content.");
            }

            if ($role === 'system') {
                if ($conversation !== []) {
                    throw new InvalidArgumentException('System messages must come before the conversation.');
                }

                $instructions[] = $content;

                continue;
            }

            $conversation[] = ['role' => $role, 'content' => $content];
        }

        if ($conversation === [] || end($conversation)['role'] !== 'user') {
            throw new InvalidArgumentException('The last message must come from the user.');
        }

        return ['instructions' => implode("\n\n", $instructions), 'messages' => $conversation];
    }

    private function validateOptions(array $options): array
    {
        if ($unknown = array_diff(array_keys($options), self::OPTIONS)) {
            throw new InvalidArgumentException('Unsupported options: '.implode(', ', $unknown).'.');
        }

        if (array_key_exists('temperature', $options)) {
            $temperature = $options['temperature'];

            if (! is_int($temperature) && ! is_float($temperature) || $temperature < 0 || $temperature > 2) {
                throw new InvalidArgumentException('temperature must be a number from 0 to 2.');
            }

            $options['temperature'] = (float) $temperature;
        }

        if (array_key_exists('maxTokens', $options)
            && (! is_int($options['maxTokens']) || $options['maxTokens'] < 1)) {
            throw new InvalidArgumentException('maxTokens must be a positive integer.');
        }

        return $options;
    }

    private function call(string $function, array $parameters = []): array
    {
        $raw = ($this->transport)($function, json_encode($parameters, JSON_THROW_ON_ERROR));
        $response = is_string($raw) ? json_decode($raw, true) : null;

        if (! is_array($response)) {
            throw new LocalAIException('bridge_failed', "{$function} returned no response.");
        }

        if (($response['status'] ?? null) === 'error') {
            throw new LocalAIException(
                strtolower((string) ($response['code'] ?? 'bridge_failed')),
                (string) ($response['message'] ?? "{$function} failed."),
            );
        }

        return $response;
    }
}
