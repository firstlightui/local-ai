<?php

namespace FirstlightUI\LocalAI\Events;

use Illuminate\Foundation\Events\Dispatchable;
use Native\Mobile\Events\Contracts\DispatchableFromNative;

/**
 * Terminal: the generation failed after it was accepted. $text holds any partial output.
 */
final class GenerationFailed implements DispatchableFromNative
{
    use Dispatchable;

    public function __construct(
        public string $id,
        public string $code,
        public string $message,
        public string $text = '',
    ) {}
}
