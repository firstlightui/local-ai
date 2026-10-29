<?php

namespace App\Providers;

use Illuminate\Support\ServiceProvider;
use Native\Mobile\Providers\BrowserServiceProvider;
use Native\Mobile\UI\NativeUIServiceProvider;
use FirstlightUI\LocalLLM\Probe\ProbeServiceProvider;

final class NativeServiceProvider extends ServiceProvider
{
    public function plugins(): array
    {
        return [
            NativeUIServiceProvider::class,
            BrowserServiceProvider::class,
            ProbeServiceProvider::class,
        ];
    }
}
