<?php

use FirstlightUI\LocalAI\Commands\PrepareAndroidKotlinCommand;

$template = dirname(__DIR__).'/vendor/nativephp/mobile/resources/androidstudio';

it('raises an older Kotlin version and leaves newer ones alone', function () {
    expect(PrepareAndroidKotlinCommand::raiseKotlinVersion("kotlin = \"2.0.0\"\nkotlinx = \"1.0\"\n"))
        ->toBe("kotlin = \"2.3.21\"\nkotlinx = \"1.0\"\n")
        ->and(PrepareAndroidKotlinCommand::raiseKotlinVersion("kotlin = \"2.4.0\"\n"))
        ->toBe("kotlin = \"2.4.0\"\n");
});

it('migrates the NativePHP template kotlinOptions block once', function () use ($template) {
    $gradle = file_get_contents("{$template}/app/build.gradle.kts");
    $migrated = PrepareAndroidKotlinCommand::migrateKotlinOptions($gradle);

    expect($migrated)->not->toContain('kotlinOptions')
        ->toContain('jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)')
        ->toContain('freeCompilerArgs.addAll("-Xsuppress-version-warnings")')
        ->toContain('allWarningsAsErrors.set(false)')
        ->toContain('buildFeatures {')
        ->and(PrepareAndroidKotlinCommand::migrateKotlinOptions($migrated))->toBe($migrated);
});

it('finds the Kotlin version in the NativePHP template catalog', function () use ($template) {
    $catalog = file_get_contents("{$template}/gradle/libs.versions.toml");

    expect(PrepareAndroidKotlinCommand::raiseKotlinVersion($catalog))
        ->toContain('kotlin = "'.PrepareAndroidKotlinCommand::KOTLIN_VERSION.'"');
});
