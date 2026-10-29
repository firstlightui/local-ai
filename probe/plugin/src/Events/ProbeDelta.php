<?php

namespace FirstlightUI\LocalAI\Probe\Events;

final class ProbeDelta
{
    public function __construct(
        public string $id,
        public int $sequence,
        public string $delta,
    ) {}
}
