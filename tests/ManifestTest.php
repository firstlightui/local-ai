<?php

use Native\Mobile\Events\Contracts\DispatchableFromNative;

$manifest = json_decode(file_get_contents(dirname(__DIR__).'/nativephp.json'), true, flags: JSON_THROW_ON_ERROR);

it('declares events that native code may dispatch into PHP', function (string $event) {
    // /_native/api/events refuses any class without this contract, so a missing
    // interface silently drops every event.
    expect(class_exists($event))->toBeTrue()
        ->and(is_subclass_of($event, DispatchableFromNative::class))->toBeTrue();
})->with($manifest['events']);

it('ships a JavaScript export for every bridge function', function (string $function) {
    $js = file_get_contents(dirname(__DIR__).'/resources/js/localAI.js');
    $export = substr($function, strlen('LocalAI.'));

    expect($js)->toMatch("/export async function {$export}\\(/")
        ->toContain("'{$function}'");
})->with(array_column($manifest['bridge_functions'], 'name'));
