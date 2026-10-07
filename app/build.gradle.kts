plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.dragosfotache.gymtracka"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.dragosfotache.gymtracka"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        compose = true
    }
    sourceSets {
        getByName("androidTest").assets.directories.add(
            "$projectDir/schemas",
        )
        // External Room/engine convergence is opt-in; ordinary device tests
        // stay offline. Explicit enabled runs fail when relay inputs are absent.
        if (providers.gradleProperty("gymtracka.roomBackend").orNull == "true") {
            getByName("androidTest").kotlin.directories.add("$projectDir/src/roomBackendTest/java")
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    // Test-only PostgreSQL driver for the guarded real-backend fixture.
    // Production code must never touch java.sql; only backend-package tests
    // load this driver through RealBackendFixture.
    testImplementation(libs.postgresql)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.okhttp.tls)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// Real-backend integration tests: JVM tests against an isolated backend started
// by scripts/run-isolated-backend.py. They reuse the debug unit-test compiled
// classes and classpath but run only the dedicated backend package, always
// execute against external state (never up-to-date or cached), and fail when
// the config is absent or discovers no tests. There is deliberately no default
// config path: the task requires -Pgymtracka.realBackendConfig=... or
// GYMTRACKA_REAL_BACKEND_CONFIG so a stale file can never be accepted silently.
// The ordinary unit-test tasks exclude this package so they stay offline.
// The provider is resolved to a plain string at configuration time (tracking
// -P/env for the configuration cache); the task validates that string at
// execution through its own properties only, capturing no script variables.
val realBackendConfigPath: String = providers.gradleProperty("gymtracka.realBackendConfig")
    .orElse(providers.environmentVariable("GYMTRACKA_REAL_BACKEND_CONFIG"))
    .getOrElse("")

tasks.register<Test>("testRealBackend") {
    description = "Runs real-backend integration tests against the isolated backend."
    group = "verification"
    // Realize the debug unit-test task and reuse its underlying file
    // collections directly: unlike TaskProvider.map this attaches no task
    // dependency, so the ordinary suite never joins this task's graph.
    val debugUnitTest = tasks.named<Test>("testDebugUnitTest").get()
    testClassesDirs = debugUnitTest.testClassesDirs
    classpath = debugUnitTest.classpath
    dependsOn("compileDebugUnitTestKotlin")
    include("com/dragosfotache/gymtracka/data/sync/backend/**")
    systemProperty("gymtracka.realBackendConfig", realBackendConfigPath)
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
    failOnNoDiscoveredTests = true
}

// Resolve a host-only launch classpath, then serve with ordinary java outside
// Gradle: a long-lived relay must not hold the serialized build slot.
tasks.register("prepareRoomBackendRelay") {
    description = "Compiles the host-only Room backend relay and writes its launch classpath."
    group = "verification"
    val runtime = tasks.named<Test>("testDebugUnitTest").get().classpath
    val destination = layout.buildDirectory.file("r10/relay-classpath.txt")
    dependsOn("compileDebugUnitTestKotlin")
    inputs.files(runtime)
    outputs.file(destination)
    doLast {
        val file = destination.get().asFile
        file.parentFile.mkdirs()
        file.writeText(runtime.asPath)
    }
}

tasks.withType<Test>().configureEach {
    if (name != "testRealBackend") {
        exclude("com/dragosfotache/gymtracka/data/sync/backend/**")
    }
}
