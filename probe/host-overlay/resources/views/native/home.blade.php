<scroll class="safe-area fill bg-theme-background">
    <column class="w-full gap-4 p-6">
        <text class="text-xl font-bold text-theme-on-surface">Local LLM feasibility probe</text>
        <text class="text-sm text-theme-on-surface-variant">Status: {{ $status }}</text>

        <column class="w-full gap-3">
            <button class="w-full" @press="generateText">Stream raw text</button>
            <button class="w-full" @press="generateChat">Stream templated chat</button>
            <button class="w-full" @press="generateLongText">Long prefill cancellation probe</button>
            <button class="w-full" @press="cancel">Cancel active request</button>
            <button class="w-full" @press="unload">Unload when idle</button>
        </column>

        <column class="w-full rounded-lg bg-theme-surface p-4">
            <text class="text-sm text-theme-on-surface">{{ $output === '' ? 'Output appears here.' : $output }}</text>
        </column>
    </column>
</scroll>
