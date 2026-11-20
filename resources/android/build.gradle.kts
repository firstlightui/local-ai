// Off-device checks for the plugin's Kotlin. NativePHP compiles src/ into the app itself;
// this build unit tests the request parser on the JVM and typechecks every source against
// android.jar and the ML Kit GenAI classes the app links.
plugins {
    kotlin("jvm") version "2.4.21"
}

val androidSdk = System.getenv("ANDROID_HOME")
    ?: System.getenv("ANDROID_SDK_ROOT")
    ?: "${System.getProperty("user.home")}/Library/Android/sdk"

val androidJar = file("$androidSdk/platforms/android-36/android.jar")

// Keep in step with nativephp.json's android.dependencies.
val mlkit = configurations.create("mlkit")

dependencies {
    mlkit("com.google.mlkit:genai-prompt:1.0.0-beta5")
    mlkit("androidx.lifecycle:lifecycle-process:2.6.1")
}

// AARs are zips; the compiler needs the classes.jar inside each one.
val mlkitClasses = layout.buildDirectory.dir("mlkit")
val extractMlkit = tasks.register("extractMlkit") {
    inputs.files(mlkit)
    outputs.dir(mlkitClasses)
    doLast {
        val target = mlkitClasses.get().asFile
        target.deleteRecursively()
        target.mkdirs()
        mlkit.files.forEach { artifact ->
            when (artifact.extension) {
                "aar" -> project.copy {
                    from(project.zipTree(artifact)) { include("classes.jar") }
                    into(target)
                    rename { "${artifact.nameWithoutExtension}.jar" }
                }
                "jar" -> artifact.copyTo(target.resolve(artifact.name), overwrite = true)
            }
        }
    }
}

sourceSets.create("hostCheck")

kotlin {
    sourceSets {
        main {
            kotlin.setSrcDirs(listOf("src"))
            kotlin.include("**/LocalAIRequest.kt")
        }
        test {
            kotlin.setSrcDirs(listOf("test"))
        }
        getByName("hostCheck") {
            kotlin.setSrcDirs(listOf("src", "host-stubs"))
        }
    }
}

dependencies {
    implementation("org.json:json:20250517")
    testImplementation(kotlin("test-junit"))
    "hostCheckCompileOnly"(files(androidJar))
    "hostCheckCompileOnly"(files(mlkitClasses.map { it.asFile.listFiles()?.toList().orEmpty() }).builtBy(extractMlkit))
    "hostCheckCompileOnly"("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}
