<?php

namespace FirstlightUI\LocalAI\Commands;

use Native\Mobile\Plugins\Commands\NativePluginHookCommand;

/**
 * ML Kit GenAI ships Kotlin 2.4 metadata. NativePHP's Android template
 * compiles with Kotlin 2.0, which can only read metadata up to 2.1. Raise the
 * host to a compiler that reads it, and replace the `kotlinOptions` block
 * that Kotlin 2.2+ rejects with the equivalent `compilerOptions` DSL.
 */
final class PrepareAndroidKotlinCommand extends NativePluginHookCommand
{
    public const KOTLIN_VERSION = '2.3.21';

    protected $signature = 'nativephp:local-ai:prepare-android';

    protected $description = 'Raise the Android host Kotlin version for ML Kit GenAI';

    public function handle(): int
    {
        if (! $this->isAndroid()) {
            return self::SUCCESS;
        }

        $catalog = $this->buildPath().'/gradle/libs.versions.toml';
        $appGradle = $this->buildPath().'/app/build.gradle.kts';

        if (! is_file($catalog) || ! is_file($appGradle)) {
            $this->error('Local AI could not find the Android Gradle files to raise the Kotlin version.');

            return self::FAILURE;
        }

        file_put_contents($catalog, self::raiseKotlinVersion(file_get_contents($catalog)));
        file_put_contents($appGradle, self::migrateKotlinOptions(file_get_contents($appGradle)));

        return self::SUCCESS;
    }

    public static function raiseKotlinVersion(string $catalog): string
    {
        return preg_replace_callback(
            '/^kotlin\s*=\s*"([^"]+)"/m',
            fn (array $match) => version_compare($match[1], self::KOTLIN_VERSION, '<')
                ? 'kotlin = "'.self::KOTLIN_VERSION.'"'
                : $match[0],
            $catalog,
            1,
        );
    }

    public static function migrateKotlinOptions(string $gradle): string
    {
        if (! preg_match('/\n([ \t]*)kotlinOptions \{\n(.*?)\n\1\}\n/s', $gradle, $match)) {
            return $gradle;
        }

        $body = $match[2];
        $lines = ['        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)'];

        if (preg_match('/jvmTarget\s*=\s*"(\d+)"/', $body, $target)) {
            $lines[0] = "        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_{$target[1]})";
        }

        if (preg_match('/freeCompilerArgs\s*\+=\s*listOf\((.*?)\)/s', $body, $args)) {
            $lines[] = '        freeCompilerArgs.addAll('.trim(preg_replace('/\s+/', ' ', $args[1])).')';
        }

        if (preg_match('/allWarningsAsErrors\s*=\s*(true|false)/', $body, $warnings)) {
            $lines[] = "        allWarningsAsErrors.set({$warnings[1]})";
        }

        $gradle = str_replace($match[0], "\n", $gradle);

        return rtrim($gradle)."\n\nkotlin {\n    compilerOptions {\n".implode("\n", $lines)."\n    }\n}\n";
    }
}
