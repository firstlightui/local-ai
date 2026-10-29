<?php

namespace FirstlightUI\LocalAI;

use RuntimeException;

/**
 * Error codes: unavailable, busy, duplicate_id, invalid_request, unknown_operation,
 * not_ready, cancelled, context_exceeded, guardrail, unsupported_language,
 * rate_limited, background_blocked, generation_failed, bridge_failed.
 */
final class LocalAIException extends RuntimeException
{
    public function __construct(
        public readonly string $errorCode,
        string $message,
    ) {
        parent::__construct($message);
    }
}
