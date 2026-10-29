<?php

namespace FirstlightUI\LocalAI\Facades;

use FirstlightUI\LocalAI\Availability;
use FirstlightUI\LocalAI\Generation;
use Illuminate\Support\Facades\Facade;

/**
 * @method static Availability availability()
 * @method static Availability download()
 * @method static Generation generate(string $prompt, ?string $instructions = null, array $options = [])
 * @method static Generation chat(array $messages, array $options = [])
 * @method static Generation generation(string $id)
 * @method static void cancel(string $id)
 * @method static array snapshot(string $id)
 *
 * @see \FirstlightUI\LocalAI\LocalAI
 */
final class LocalAI extends Facade
{
    protected static function getFacadeAccessor(): string
    {
        return \FirstlightUI\LocalAI\LocalAI::class;
    }
}
