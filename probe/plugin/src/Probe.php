<?php

namespace NativePHP\LocalAI\Probe;

use RuntimeException;

final class Probe
{
    public function start(array $request): string
    {
        return $this->call('LocalAIProbe.Start', $request)['id'];
    }

    public function cancel(string $id): void
    {
        $this->call('LocalAIProbe.Cancel', ['id' => $id]);
    }

    public function unload(): void
    {
        $this->call('LocalAIProbe.Unload', []);
    }

    public function setBackgrounded(bool $backgrounded): void
    {
        $this->call('LocalAIProbe.SetBackgrounded', ['backgrounded' => $backgrounded]);
    }

    private function call(string $function, array $parameters): array
    {
        if (! function_exists('nativephp_call')) {
            throw new RuntimeException('The Local AI probe requires a NativePHP runtime.');
        }

        $response = json_decode(nativephp_call($function, json_encode($parameters)), true);
        if (! is_array($response)) {
            throw new RuntimeException('Native probe call failed.');
        }

        if (($response['status'] ?? null) === 'error') {
            throw new RuntimeException($response['message'] ?? 'Native probe call failed.');
        }

        return $response;
    }
}
