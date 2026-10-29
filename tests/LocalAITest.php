<?php

use FirstlightUI\LocalAI\LocalAI;
use FirstlightUI\LocalAI\LocalAIException;

function fakeLocalAI(array &$calls, array $responses = []): LocalAI
{
    return new LocalAI(function (string $function, string $parameters) use (&$calls, $responses) {
        $calls[] = [$function, json_decode($parameters, true)];

        return json_encode($responses[$function] ?? []);
    });
}

it('sends system messages as instructions and keeps the conversation order', function () {
    $calls = [];
    $generation = fakeLocalAI($calls)->chat([
        ['role' => 'system', 'content' => 'Be brief.'],
        ['role' => 'system', 'content' => 'Use British English.'],
        ['role' => 'user', 'content' => 'Hi'],
        ['role' => 'assistant', 'content' => 'Hello.'],
        ['role' => 'user', 'content' => 'Name a colour.'],
    ], ['temperature' => 0.7, 'maxTokens' => 64]);

    [$function, $parameters] = $calls[0];
    expect($function)->toBe('LocalAI.Generate')
        ->and($parameters['id'])->toBe($generation->id)
        ->and($parameters['instructions'])->toBe("Be brief.\n\nUse British English.")
        ->and(array_column($parameters['messages'], 'role'))->toBe(['user', 'assistant', 'user'])
        ->and($parameters['temperature'])->toBe(0.7)
        ->and($parameters['maxTokens'])->toBe(64);
});

it('rejects invalid requests before calling native code', function (array $messages, array $options) {
    $calls = [];
    expect(fn () => fakeLocalAI($calls)->chat($messages, $options))->toThrow(InvalidArgumentException::class);
    expect($calls)->toBe([]);
})->with([
    'ends with assistant' => [[['role' => 'user', 'content' => 'a'], ['role' => 'assistant', 'content' => 'b']], []],
    'late system message' => [[['role' => 'user', 'content' => 'a'], ['role' => 'system', 'content' => 'b'], ['role' => 'user', 'content' => 'c']], []],
    'only system' => [[['role' => 'system', 'content' => 'a']], []],
    'unknown option' => [[['role' => 'user', 'content' => 'a']], ['seed' => 1]],
    'temperature out of range' => [[['role' => 'user', 'content' => 'a']], ['temperature' => 2.5]],
    'zero max tokens' => [[['role' => 'user', 'content' => 'a']], ['maxTokens' => 0]],
]);

it('turns native errors into typed exceptions', function () {
    $calls = [];
    $localAI = fakeLocalAI($calls, [
        'LocalAI.Generate' => ['status' => 'error', 'code' => 'busy', 'message' => 'Too many generations are queued.'],
    ]);

    expect(fn () => $localAI->generate('Hi'))
        ->toThrow(fn (LocalAIException $e) => expect($e->errorCode)->toBe('busy'));
});

it('returns results only for completed generations', function (array $snapshot, ?string $text, ?string $code) {
    $calls = [];
    $generation = fakeLocalAI($calls, ['LocalAI.Status' => $snapshot])->generation('op-1');

    if ($text !== null) {
        expect($generation->result())->toBe($text);

        return;
    }

    expect(fn () => $generation->result())
        ->toThrow(fn (LocalAIException $e) => expect($e->errorCode)->toBe($code));
})->with([
    'completed' => [['status' => 'completed', 'text' => 'Blue.'], 'Blue.', null],
    'running' => [['status' => 'running', 'text' => 'Bl'], null, 'not_ready'],
    'cancelled with partial text' => [['status' => 'cancelled', 'text' => 'Bl'], null, 'cancelled'],
    'failed' => [['status' => 'failed', 'errorCode' => 'guardrail', 'errorMessage' => 'Blocked.'], null, 'guardrail'],
]);
