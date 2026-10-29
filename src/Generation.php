<?php

namespace FirstlightUI\LocalAI;

/**
 * Handle for a native generation. Native state is authoritative; events are
 * notifications, so reconcile with snapshot() after navigation or missed events.
 */
final class Generation
{
    public function __construct(
        public readonly string $id,
        private readonly LocalAI $localAI,
    ) {}

    /**
     * @return array{id: string, status: string, text: string, sequence: int, errorCode: string, errorMessage: string}
     */
    public function snapshot(): array
    {
        return $this->localAI->snapshot($this->id);
    }

    /**
     * One of queued, running, completed, failed or cancelled.
     */
    public function status(): string
    {
        return $this->snapshot()['status'];
    }

    /**
     * Text produced so far, including partial output after failure or cancellation.
     */
    public function text(): string
    {
        return $this->snapshot()['text'];
    }

    public function cancel(): void
    {
        $this->localAI->cancel($this->id);
    }

    /**
     * The completed response text.
     *
     * @throws LocalAIException not_ready, cancelled, or the failure's code
     */
    public function result(): string
    {
        $snapshot = $this->snapshot();

        return match ($snapshot['status']) {
            'completed' => $snapshot['text'],
            'cancelled' => throw new LocalAIException('cancelled', 'The generation was cancelled.'),
            'failed' => throw new LocalAIException($snapshot['errorCode'], $snapshot['errorMessage']),
            default => throw new LocalAIException('not_ready', 'The generation has not finished.'),
        };
    }
}
