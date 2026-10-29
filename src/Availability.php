<?php

namespace FirstlightUI\LocalAI;

final class Availability
{
    /**
     * @param  string  $status  available, downloadable, downloading or unavailable
     * @param  string  $reason  Platform detail when not available, for example
     *                          device_not_eligible, not_enabled, model_not_ready or os_unsupported
     * @param  string  $platform  ios or android
     */
    public function __construct(
        public readonly string $status,
        public readonly string $reason,
        public readonly string $platform,
    ) {}

    public static function fromArray(array $data): self
    {
        return new self(
            (string) ($data['status'] ?? 'unavailable'),
            (string) ($data['reason'] ?? ''),
            (string) ($data['platform'] ?? ''),
        );
    }

    public function isAvailable(): bool
    {
        return $this->status === 'available';
    }
}
