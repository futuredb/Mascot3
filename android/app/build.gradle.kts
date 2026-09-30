import java.io.File
import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

fun dotenv(path: String): Map<String, String> = rootProject.file(path).takeIf { it.isFile }
    ?.readLines()
    ?.mapNotNull { line ->
        val clean = line.trim()
        if (clean.isBlank() || clean.startsWith("#") || '=' !in clean) return@mapNotNull null
        val (key, value) = clean.split('=', limit = 2)
        key.trim() to value.trim().trim('"', '\'')
    }
    ?.toMap()
    .orEmpty()

fun quotedBuildConfig(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

val localEnvironment = dotenv("../.env")
val embeddedOpenRouterKey = providers.gradleProperty("MASCOT3_OPENROUTER_API_KEY").orNull
    ?: localEnvironment["OPENROUTER_API_KEY"]
    ?: ""

// Release keys/passwords are supplied by the build host, never committed to this repository.
val releaseStorePath = providers.environmentVariable("MASCOT3_RELEASE_STORE_FILE").orNull
val releaseStorePassword = providers.environmentVariable("MASCOT3_RELEASE_STORE_PASSWORD").orNull
val releaseAlias = providers.environmentVariable("MASCOT3_RELEASE_KEY_ALIAS").orElse("mascots-release").get()
val releaseKeyPassword = providers.environmentVariable("MASCOT3_RELEASE_KEY_PASSWORD").orNull
    ?: releaseStorePassword

android {
    namespace = "com.generativemascot.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.mascot3"
        minSdk = 26
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 41
        versionName = "0.1.40"
        val serverUrl = providers.gradleProperty("MASCOT3_SERVER_URL").orElse("http://127.0.0.1:8788")
        val clientToken = providers.gradleProperty("MASCOT3_CLIENT_TOKEN").orElse("")
        buildConfigField("String", "API_BASE_URL", quotedBuildConfig(serverUrl.get()))
        buildConfigField("String", "MASCOT3_CLIENT_TOKEN", quotedBuildConfig(clientToken.get()))
        buildConfigField("String", "OPENROUTER_API_KEY", quotedBuildConfig(embeddedOpenRouterKey))
    }

    signingConfigs {
        if (!releaseStorePath.isNullOrBlank() && !releaseStorePassword.isNullOrBlank()) {
            create("production") {
                storeFile = file(releaseStorePath)
                storePassword = releaseStorePassword
                keyAlias = releaseAlias
                keyPassword = releaseKeyPassword
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        create("flowdemo") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".flowdemo"
            versionNameSuffix = "-demo6"
            matchingFallbacks += "debug"
            signingConfig = signingConfigs.getByName("debug")
            resValue("string", "app_name", "Маскот · Демо")
            // Offline preview must never inherit the production credentials from .env.
            buildConfigField("String", "OPENROUTER_API_KEY", "\"\"")
            buildConfigField("String", "MASCOT3_CLIENT_TOKEN", "\"\"")
            buildConfigField("String", "API_BASE_URL", "\"http://127.0.0.1:1\"")
        }
        release {
            isDebuggable = false
            signingConfigs.findByName("production")?.let { signingConfig = it }
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions { unitTests.isIncludeAndroidResources = true }
    testBuildType = providers.gradleProperty("MASCOT3_TEST_BUILD_TYPE").orElse("debug").get()
}

tasks.register("validateProductionConfiguration") {
    doLast {
        check(!releaseStorePath.isNullOrBlank() && !releaseStorePassword.isNullOrBlank()
            && !releaseKeyPassword.isNullOrBlank() && file(releaseStorePath).isFile) {
            "Release requires a production keystore and MASCOT3_RELEASE_* signing variables"
        }
        val server = providers.gradleProperty("MASCOT3_SERVER_URL").orNull.orEmpty()
        val uri = runCatching { URI(server) }.getOrNull()
        check(uri?.scheme == "https" && !uri.host.isNullOrBlank()
            && uri.host !in setOf("localhost", "127.0.0.1", "10.0.2.2") && uri.userInfo == null) {
            "Release requires an explicit HTTPS production server URL"
        }
        check(providers.gradleProperty("MASCOT3_CLIENT_TOKEN").orNull?.isNotBlank() == true) {
            "Release requires the existing team-server client token"
        }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn("validateProductionConfiguration")
}

// Android 16 Robolectric images need Java 21+. Keep Android compilation on Java 17,
// while allowing CI/local tests to use a newer JVM without changing the app runtime.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
}
providers.gradleProperty("MASCOT3_TEST_JAVA_HOME").orNull?.let { testJavaHome ->
    tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
        executable = File(testJavaHome, "bin/java").absolutePath
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.glance:glance-material3:1.1.1")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-gif:2.7.0")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
