// Pure Kotlin/JVM module: all business rules live here (budget math, reserve
// floor, notification parsing, de-duplication, classification, net worth, FX,
// backup crypto). No Android dependencies, so it is fast to unit-test.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(libs.junit)
}
