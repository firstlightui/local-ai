<?php

use Native\Mobile\Events\Contracts\DispatchableFromNative;

$manifest = json_decode(file_get_contents(dirname(__DIR__).'/nativephp.json'), true, flags: JSON_THROW_ON_ERROR);

it('declares events that native code may dispatch into PHP', function (string $event) {
    // /_native/api/events refuses any class without this contract, so a missing
    // interface silently drops every event.
    expect(class_exists($event))->toBeTrue()
        ->and(is_subclass_of($event, DispatchableFromNative::class))->toBeTrue();
})->with($manifest['events']);
