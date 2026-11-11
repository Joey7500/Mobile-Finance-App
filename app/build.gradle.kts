import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// ── Release signing ────────────────────────────────────────────────────────────
// Values come from the git-ignored `keystore.properties` (local builds) or from
// environment variables injected by GitHub Actions secrets (release workflow).
// Nothing secret is ever written in this file. See docs/SECURITY.md.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun signingValue(propKey: String, envKey: String): String? =
    keystoreProps.getProperty(propKey)?.takeIf { it.isNotBlank() }
        ?: System.getenv(envKey)?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingValue("storeFile", "VAULTFLOW_KEYSTORE_PATH")

android {
    namespace = "io.github.joey7500.vaultflow"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.joey7500.vaultflow"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = signingValue("storePassword", "VAULTFLOW_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "VAULTFLOW_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "VAULTFLOW_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/LICENSE.md", "META-INF/NOTICE.md", "META-INF/LICENSE.txt", "META-INF/NOTICE.txt")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    // Room schema history is committed so every DB migration is reviewable.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core:domain"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.biometric)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.sqlite.ktx)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // Read-only IMAP access to the Gmail inbox that receives bank e-mails.
    implementation(libs.android.mail)
    implementation(libs.android.activation)
    implementation(libs.androidx.work.runtime.ktx)

    // Encrypted backups in the user's own Google Drive (drive.file scope only).
    implementation(libs.play.services.auth)
    implementation(libs.kotlinx.coroutines.play.services)

    // Home-screen widget.
    implementation(libs.androidx.glance.appwidget)

    testImplementation(libs.junit)
}
