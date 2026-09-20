<?php

namespace NativePHP\LocalAI\Probe;

use Illuminate\Support\ServiceProvider;

final class ProbeServiceProvider extends ServiceProvider
{
    public function register(): void
    {
        $this->app->singleton(Probe::class);
    }
}
