<?php

namespace App\NativeComponents;

use Illuminate\View\View;
use Native\Mobile\Attributes\On;
use Native\Mobile\Edge\NativeComponent;
use NativePHP\LocalAI\Probe\Events\ProbeDelta;
use NativePHP\LocalAI\Probe\Events\ProbeTerminal;
use NativePHP\LocalAI\Probe\Probe;

final class Home extends NativeComponent
{
    public string $status = 'idle';
    public string $output = '';
    public ?string $operationId = null;

    public function generateText(Probe $probe): void
    {
        $this->start($probe, [
            'mode' => 'text',
            'prompt' => 'Write exactly three short words naming primary colours.',
        ]);
    }

    public function generateChat(Probe $probe): void
    {
        $this->start($probe, [
            'mode' => 'chat',
            'messages' => [
                ['role' => 'system', 'content' => 'Reply concisely.'],
                ['role' => 'user', 'content' => 'Name two moons in our solar system.'],
            ],
        ]);
    }

    public function generateLongText(Probe $probe): void
    {
        $this->start($probe, [
            'mode' => 'text',
            'prompt' => implode(' ', array_fill(0, 512, 'alpha beta gamma delta')),
        ]);
    }

    public function cancel(Probe $probe): void
    {
        if ($this->operationId !== null) {
            $probe->cancel($this->operationId);
            $this->status = 'cancellation requested';
        }
    }

    public function unload(Probe $probe): void
    {
        $probe->unload();
        $this->status = 'unload requested';
    }

    #[On(ProbeDelta::class)]
    public function onDelta(string $id, int $sequence, string $delta): void
    {
        if ($id === $this->operationId) {
            $this->status = "streaming #{$sequence}";
            $this->output .= $delta;
        }
    }

    #[On(ProbeTerminal::class)]
    public function onTerminal(
        string $id,
        string $status,
        ?string $text = null,
        ?string $phase = null,
        ?string $error = null,
    ): void {
        if ($id !== $this->operationId) {
            return;
        }

        $this->status = $phase === null ? $status : "{$status}: {$phase}";
        if ($text !== null) {
            $this->output = $text;
        }
        if ($error !== null) {
            $this->output = $error;
        }
    }

    public function render(): View
    {
        return view('native.home');
    }

    private function start(Probe $probe, array $input): void
    {
        $this->output = '';
        $this->status = 'accepted';
        $this->operationId = (string) str()->uuid();
        $probe->start($input + [
            'id' => $this->operationId,
            'modelPath' => env('LOCAL_AI_MODEL_PATH', 'qwen2-0.5b-instruct'),
            'maxTokens' => 128,
        ]);
    }
}
