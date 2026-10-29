<?php

namespace FirstlightUI\LocalAI\Events;

use Illuminate\Foundation\Events\Dispatchable;
use Native\Mobile\Events\Contracts\DispatchableFromNative;

/**
 * A decoded text fragment. Fragments arrive in increasing sequence order; event count is not token count.
 */
final class TextDeltaGenerated implements DispatchableFromNative
{
    use Dispatchable;

    public function __construct(
        public string $id,
        public int $sequence,
        public string $delta,
    ) {}
}
