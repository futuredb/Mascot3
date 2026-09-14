import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val localSecrets = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use(::load)
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

val appSecrets = dotenv("../.env")
val allowEmbeddedProviderKey = providers.gradleProperty("MASCOT3_EMBED_OPENROUTER_KEY")
    .orElse("false")
    .map(String::toBoolean)
val embeddedOpenRouterKey = if (allowEmbeddedProviderKey.get()) {
    localSecrets.getProperty("openrouter.api.key") ?: appSecrets["OPENROUTER_API_KEY"] ?: ""
} else {
    ""
}

android {
    namespace = "com.generativemascot.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.mascot3"
        minSdk = 26
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 1
        versionName = "0.1.0"
        val serverUrl = providers.gradleProperty("MASCOT3_SERVER_URL").orElse("http://127.0.0.1:8788")
        val clientToken = providers.gradleProperty("MASCOT3_CLIENT_TOKEN").orElse("")
        buildConfigField("String", "API_BASE_URL", quotedBuildConfig(serverUrl.get()))
        buildConfigField("String", "MASCOT3_CLIENT_TOKEN", quotedBuildConfig(clientToken.get()))
        buildConfigField("boolean", "DIRECT_OPENAI_GENERATION", "false")
        buildConfigField("String", "OPENAI_API_KEY", "\"\"")
        buildConfigField("String", "OPENROUTER_API_KEY", quotedBuildConfig(embeddedOpenRouterKey))
    }

    buildTypes {
        release {
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
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
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
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
