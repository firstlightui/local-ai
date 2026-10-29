<?php

namespace FirstlightUI\LocalAI\Events;

use Illuminate\Foundation\Events\Dispatchable;
use Native\Mobile\Events\Contracts\DispatchableFromNative;

/**
 * Android model download progress: $status is started, downloading, completed or failed.
 */
final class ModelDownloadProgress implements DispatchableFromNative
{
    use Dispatchable;

    public function __construct(
        public string $status,
        public int $bytesDownloaded = 0,
        public int $bytesToDownload = 0,
        public string $message = '',
    ) {}
}
