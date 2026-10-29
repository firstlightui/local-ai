<?php

namespace FirstlightUI\LocalAI;

use FirstlightUI\LocalAI\Commands\PrepareAndroidKotlinCommand;
use Illuminate\Support\ServiceProvider;

final class LocalAIServiceProvider extends ServiceProvider
{
    public function register(): void
    {
        $this->app->singleton(LocalAI::class, fn () => new LocalAI);
    }

    public function boot(): void
    {
        if ($this->app->runningInConsole()) {
            $this->commands([PrepareAndroidKotlinCommand::class]);
        }
    }
}
