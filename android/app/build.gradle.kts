import java.util.Properties
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

// ---- Push (decision 026) ----
// Firebase config is Harsha's credential: app/google-services.json (gitignored), copied from
// ~/risime-keys/. Without it the plugin isn't applied and the app builds and runs without push.
val pushConfigured = file("google-services.json").isFile
if (pushConfigured) pluginManager.apply("com.google.gms.google-services")

// ---- E2EE groundwork (decisions 012 §5a, 031): native MLS core in DEBUG builds only ----
// Built by ../scripts/build-rust-android when rustup's cargo and an NDK exist (spark2); otherwise
// (laptop, CI without Rust) the app builds without crypto. Opt out with -Prisime.crypto=false.
val repoDir: File = rootProject.projectDir.parentFile
val cargoBin = File(System.getProperty("user.home"), ".cargo/bin/cargo")
val ndkDir: File? = (System.getenv("ANDROID_NDK_HOME")?.let(::File)?.takeIf { File(it, "toolchains").isDirectory })
    ?: File(System.getenv("ANDROID_HOME") ?: "${System.getProperty("user.home")}/Android/Sdk", "ndk")
        .listFiles()?.filter { File(it, "toolchains").isDirectory }?.maxByOrNull { it.name }
val cryptoToolchain: Boolean = providers.gradleProperty("risime.crypto").orNull != "false" &&
    cargoBin.canExecute() && ndkDir != null &&
    File(repoDir, "scripts/build-rust-android").canExecute() && File(repoDir, "crypto/risime-mls-ffi").isDirectory
if (!cryptoToolchain) logger.lifecycle("risime: Rust/NDK not found (or -Prisime.crypto=false): debug build without the MLS core")

// ---- Version (decision 003) ----
// versionName = the repo's top-level VERSION file (trimmed): "X.Y.Z" or "X.Y.Z-nightly.N".
// versionCode = major*1_000_000 + minor*10_000 + patch*100 + (N for -nightly.N, 99 for a final),
// so codes always increase: 0.2.0-nightly.1 = 20001 < 0.2.0 = 20099 < 0.3.0-nightly.1 = 30001.
// Limits keep the parts from overlapping: minor <= 99, patch <= 99, N <= 98.
fun risiVersionCode(version: String): Int {
    val m = Regex("""^(\d+)\.(\d+)\.(\d+)(?:-nightly\.(\d+))?$""").matchEntire(version)
        ?: throw GradleException("VERSION '$version' is not X.Y.Z or X.Y.Z-nightly.N")
    val (major, minor, patch, nightly) = m.destructured
    val n = if (nightly.isEmpty()) 99 else nightly.toInt()
    if (minor.toInt() > 99 || patch.toInt() > 99 || n !in 1..99 || (nightly.isNotEmpty() && n > 98)) {
        throw GradleException("VERSION '$version' is out of range for the versionCode formula")
    }
    return major.toInt() * 1_000_000 + minor.toInt() * 10_000 + patch.toInt() * 100 + n
}

// Self-check of the formula against the examples in decision 003 (runs at configuration time).
check(risiVersionCode("0.2.0-nightly.1") == 20001 && risiVersionCode("0.2.0") == 20099 &&
    risiVersionCode("1.2.3-nightly.4") == 1_020_304) { "versionCode formula self-check failed" }

val risiVersionName: String = providers
    .fileContents(rootProject.layout.projectDirectory.file("../VERSION")).asText.get().trim()
val risiVersionCodeValue: Int = risiVersionCode(risiVersionName)

// ---- Release signing (decision 003) ----
// Read from $HOME/risime-keys/keystore.properties when it exists (spark2); otherwise the release
// build is unsigned (e.g. on the laptop). Machine paths and secrets never live in the repo.
val releaseKeystoreProps: Properties? =
    File(System.getProperty("user.home"), "risime-keys/keystore.properties")
        .takeIf { it.isFile }
        ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }

android {
    namespace = "lk.codegen.risime"
    compileSdk = 37
    // Pinned so spark2 (ARM64) uses its drop-in arm64 build-tools; AGP would otherwise fetch x86 36.0.0.
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "lk.codegen.risime"
        minSdk = 26
        targetSdk = 37
        versionCode = risiVersionCodeValue
        versionName = risiVersionName
        // Release: spark2 via Caddy at risime.risicloud.ai. Debug overrides it with the laptop tunnel.
        // Editable in Settings / on login in every build.
        buildConfigField("String", "DEFAULT_SERVER_URL", "\"https://risime.risicloud.ai\"")
        // Keycloak redirects (decision 014): custom scheme per build type so both can be installed.
        manifestPlaceholders["appAuthRedirectScheme"] = "ai.risicloud.risime"
        buildConfigField("String", "OIDC_REDIRECT_URI", "\"ai.risicloud.risime://callback\"")
        buildConfigField("String", "OIDC_LOGOUT_REDIRECT_URI", "\"ai.risicloud.risime://logout\"")
        // In-app updater (decision 016): release only.
        buildConfigField("boolean", "UPDATER_ENABLED", "true")
        buildConfigField("String", "UPDATE_BASE_URL", "\"https://risicloud.ai/app/risime/\"")
        buildConfigField("boolean", "PUSH_CONFIGURED", pushConfigured.toString())
        // Native MLS core (0.3 groundwork): never in release until E2EE ships.
        buildConfigField("boolean", "CRYPTO_AVAILABLE", "false")
    }

    signingConfigs {
        if (releaseKeystoreProps != null) {
            create("release") {
                storeFile = file(releaseKeystoreProps.getProperty("storeFile"))
                storePassword = releaseKeystoreProps.getProperty("storePassword")
                keyAlias = releaseKeystoreProps.getProperty("keyAlias")
                keyPassword = releaseKeystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Installs side by side with the release build (decision 003).
            applicationIdSuffix = ".debug"
            // Laptop tunnel port (emulator -> 10.0.2.2:4400 -> spark2 127.0.0.1:4000).
            buildConfigField("String", "DEFAULT_SERVER_URL", "\"http://10.0.2.2:4400\"")
            manifestPlaceholders["appAuthRedirectScheme"] = "ai.risicloud.risime.debug"
            buildConfigField("String", "OIDC_REDIRECT_URI", "\"ai.risicloud.risime.debug://callback\"")
            buildConfigField("String", "OIDC_LOGOUT_REDIRECT_URI", "\"ai.risicloud.risime.debug://logout\"")
            // Debug builds (.debug id, debug key) never self-update.
            buildConfigField("boolean", "UPDATER_ENABLED", "false")
            buildConfigField("boolean", "CRYPTO_AVAILABLE", cryptoToolchain.toString())
        }
        release {
            // R8/minify stays off until the prod release (decision 003).
            isMinifyEnabled = false
            if (releaseKeystoreProps != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    packaging {
        // JNA ships ABIs Android 8+ can't run (minSdk 26); keep the APK lean.
        jniLibs.excludes += listOf("lib/armeabi/**", "lib/mips/**", "lib/mips64/**")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // Exposes merged assets (libphonenumber metadata) to JVM unit tests via test_config.properties.
        unitTests.isIncludeAndroidResources = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// Exported Room schemas are committed: every version bump needs a Migration (no destructive fallback).
room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)

    implementation(libs.okhttp)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.libphonenumber)
    implementation(libs.appauth)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.appcompat) // Theme.AppCompat for AppAuth activities + biometric dialog
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging) // push wake-ups (decision 026); inert without google-services.json
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment.ktx)

    // JNA for the UniFFI bindings: debug only until 0.3 (release stays without it).
    debugImplementation(libs.jna) { artifact { type = "aar" } }
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.sqlite.bundled.jvm) // JVM SQLite for the Room migration test
}

// ---- Contract examples -> unit test resources ----
// The tests parse the real contract/v1/examples/*.json (never a copy committed under android/).
abstract class CopyContractExamples : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val source: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile.resolve("contract/v1/examples")
        out.deleteRecursively()
        out.mkdirs()
        val files = source.get().asFile.listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }
        files.forEach { it.copyTo(out.resolve(it.name)) }
        out.resolve("index.txt").writeText(files.joinToString("\n") { it.name })
    }
}

val copyContractExamples = tasks.register<CopyContractExamples>("copyContractExamples") {
    source.set(rootProject.layout.projectDirectory.dir("../contract/v1/examples"))
    outputDir.set(layout.buildDirectory.dir("generated/contractExamples"))
}

androidComponents {
    onVariants { variant ->
        (variant as? com.android.build.api.variant.HasUnitTest)?.unitTest?.sources?.resources
            ?.addGeneratedSourceDirectory(copyContractExamples, CopyContractExamples::outputDir)
    }
}

// ---- Merged-manifest regression test (AppAuth activities need an AppCompat theme) ----
// Unit tests read the merged debug + release manifests; the paths come in as system properties.
val mergedManifestDebug = layout.buildDirectory.file("intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml")
val mergedManifestRelease = layout.buildDirectory.file("intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml")
tasks.withType<Test>().configureEach {
    dependsOn("processDebugManifest", "processReleaseManifest")
    inputs.files(mergedManifestDebug, mergedManifestRelease).withPathSensitivity(PathSensitivity.NONE)
    inputs.dir(layout.projectDirectory.dir("src/main/res/values")).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("risime.mergedManifest.debug", mergedManifestDebug.get().asFile.absolutePath)
    systemProperty("risime.mergedManifest.release", mergedManifestRelease.get().asFile.absolutePath)
    systemProperty("risime.schemas", layout.projectDirectory.dir("schemas").asFile.absolutePath)
    systemProperty("risime.themes", layout.projectDirectory.file("src/main/res/values/themes.xml").asFile.absolutePath)
    // Opt-in live interop (LiveInteropTest): forwarded explicitly, never cached, output shown.
    val interop = providers.environmentVariable("RISIME_INTEROP_CONFIG").orNull
    if (!interop.isNullOrBlank()) {
        environment("RISIME_INTEROP_CONFIG", interop)
        outputs.upToDateWhen { false }
        testLogging.showStandardStreams = true
    }
}

// ---- Native MLS core: cargo build for the debug variant ----
abstract class CargoBuildAndroid : DefaultTask() {
    @get:Inject abstract val exec: ExecOperations

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Input abstract val abis: Property<String>

    @get:Input abstract val profileFlag: Property<String>

    @get:Internal abstract val script: RegularFileProperty

    @get:OutputDirectory abstract val jniLibsDir: DirectoryProperty

    @get:OutputDirectory abstract val kotlinDir: DirectoryProperty

    @TaskAction
    fun build() {
        val args = mutableListOf(script.get().asFile.absolutePath, "--abis", abis.get())
        profileFlag.get().takeIf { it.isNotEmpty() }?.let { args += it }
        args += listOf("--out", jniLibsDir.get().asFile.absolutePath, "--kotlin-out", kotlinDir.get().asFile.absolutePath)
        exec.exec { commandLine(args) }
    }
}

if (cryptoToolchain) {
    val cargoBuildAndroid = tasks.register<CargoBuildAndroid>("cargoBuildAndroid") {
        description = "Builds crypto/risime-mls-ffi for arm64-v8a + x86_64 (debug app only)"
        script.set(File(repoDir, "scripts/build-rust-android"))
        abis.set("arm64-v8a,x86_64")
        // Rust release profile by default: the cargo debug profile makes 59-61 MB .so files per ABI
        // (stored uncompressed in the APK). -Prisime.rustProfile=debug for a debuggable core.
        profileFlag.set(if (providers.gradleProperty("risime.rustProfile").orNull == "debug") "--debug" else "")
        sources.from(
            fileTree(File(repoDir, "crypto")) { exclude("target/**") },
            File(repoDir, "scripts/build-rust-android"),
            File(repoDir, "scripts/android-ld"),
        )
        jniLibsDir.set(layout.buildDirectory.dir("rustJniLibs"))
        kotlinDir.set(layout.buildDirectory.dir("generated/uniffi"))
    }
    androidComponents {
        onVariants(selector().withBuildType("debug")) { variant ->
            variant.sources.jniLibs?.addGeneratedSourceDirectory(cargoBuildAndroid, CargoBuildAndroid::jniLibsDir)
            variant.sources.kotlin?.addGeneratedSourceDirectory(cargoBuildAndroid, CargoBuildAndroid::kotlinDir)
            variant.sources.kotlin?.addStaticSourceDirectory("src/debugCrypto/kotlin")
        }
    }
}

// Packaging test inputs: the merged native libs of both variants (CryptoPackagingTest).
tasks.withType<Test>().configureEach {
    dependsOn("mergeDebugNativeLibs", "mergeReleaseNativeLibs")
    systemProperty("risime.nativeLibs.debug", layout.buildDirectory.dir("intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib").get().asFile.absolutePath)
    systemProperty("risime.nativeLibs.release", layout.buildDirectory.dir("intermediates/merged_native_libs/release/mergeReleaseNativeLibs/out/lib").get().asFile.absolutePath)
    systemProperty("risime.buildConfig.release", layout.buildDirectory.file("generated/source/buildConfig/release/lk/codegen/risime/BuildConfig.java").get().asFile.absolutePath)
    dependsOn("generateReleaseBuildConfig")
}
