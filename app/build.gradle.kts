import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Mesma chave em builds locais e no GitHub Actions: sem isso o celular recusa a atualização.
val keyProps = Properties().apply { rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use(::load) }
fun secret(name: String): String? = System.getenv(name) ?: keyProps.getProperty(name)
val keystoreFile = secret("BRASCAN_KEYSTORE")?.let { rootProject.file(it) }?.takeIf { it.exists() }

android {
    namespace = "com.brastoy.brascan"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.brastoy.brascan"
        minSdk = 26
        targetSdk = 35
        versionCode = System.getenv("VERSION_CODE")?.toInt() ?: 3
        versionName = System.getenv("VERSION_NAME") ?: "1.0-local"
        buildConfigField("String", "UPDATE_REPO", "\"${System.getenv("UPDATE_REPO") ?: "luizclaudio085/brascan"}\"")
    }
    signingConfigs {
        if (keystoreFile != null) create("brascan") {
            storeFile = keystoreFile
            storePassword = secret("BRASCAN_STORE_PASSWORD")
            keyAlias = secret("BRASCAN_KEY_ALIAS")
            keyPassword = secret("BRASCAN_KEY_PASSWORD")
        }
    }
    buildTypes {
        val signing = signingConfigs.findByName("brascan")
        debug { if (signing != null) signingConfig = signing }
        release { isMinifyEnabled = true; isShrinkResources = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt")); signingConfig = signing ?: signingConfigs.getByName("debug") }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(bom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("com.google.ar:core:1.54.0")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    testImplementation("junit:junit:4.13.2")
}
