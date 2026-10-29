<?php

namespace FirstlightUI\LocalAI\Probe\Events;

final class ProbeTerminal
{
    public function __construct(
        public string $id,
        public string $status,
        public ?string $text = null,
        public ?string $phase = null,
        public ?string $error = null,
    ) {}
}
