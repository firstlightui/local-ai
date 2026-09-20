<?php

namespace NativePHP\LocalAI\Probe\Events;

final class ProbeDelta
{
    public function __construct(
        public string $id,
        public int $sequence,
        public string $delta,
    ) {}
}
