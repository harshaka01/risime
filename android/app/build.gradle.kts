plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "lk.codegen.risime"
    compileSdk = 37
    // Pinned so spark2 (ARM64) uses its drop-in arm64 build-tools; AGP would otherwise fetch x86 36.0.0.
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "lk.codegen.risime"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        // Release builds have no default server in 0.1.
        buildConfigField("String", "DEFAULT_SERVER_URL", "\"\"")
    }

    buildTypes {
        debug {
            // Laptop tunnel port (emulator -> 10.0.2.2:4400 -> spark2 127.0.0.1:4000).
            buildConfigField("String", "DEFAULT_SERVER_URL", "\"http://10.0.2.2:4400\"")
        }
        release {
            isMinifyEnabled = false
        }
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

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
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
