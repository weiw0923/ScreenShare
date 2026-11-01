plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
// unified version, single source of truth at repo root
import java.util.Properties

val ver = Properties().apply {
    val candidates = listOf(
        file("../../version.properties"),   // app/ -> github/ (repo root)
        rootProject.file("../../version.properties")
    )
    val f = candidates.firstOrNull { it.exists() }
        ?: error("version.properties not found in any of: " + candidates.map { it.absolutePath })
    f.inputStream().use { load(it) }
}
android {
    namespace = "com.screenshare.tv"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.screenshare.tv"
        minSdk = 24
        targetSdk = 34
        versionCode = ver.getProperty("versionCode")?.toInt() ?: 1
        versionName = ver.getProperty("versionName") ?: "1.0"
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
