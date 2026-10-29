<?php

namespace FirstlightUI\LocalAI\Events;

use Illuminate\Foundation\Events\Dispatchable;
use Native\Mobile\Events\Contracts\DispatchableFromNative;

/**
 * Terminal: the generation finished successfully.
 */
final class GenerationCompleted implements DispatchableFromNative
{
    use Dispatchable;

    public function __construct(
        public string $id,
        public string $text,
    ) {}
}
