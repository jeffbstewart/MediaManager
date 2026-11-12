plugins {
    alias(libs.plugins.kotlin.jvm)
}

repositories {
    mavenCentral()
}

// Target JVM 17 so this module is consumable by Android (android-tv),
// transcode-buddy, and the server. Source/target bump requires updating
// the lowest consumer (currently android-tv at JVM 17).
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // `api` so consumers transitively see SLF4J — they use it directly.
    api(libs.slf4j.api)

    // OTel is an implementation detail of BinnacleExporter. Consumers
    // that don't call BinnacleExporter never touch these classes.
    implementation(platform(libs.opentelemetry.bom))
    implementation(libs.opentelemetry.api)
    implementation(libs.opentelemetry.sdk)
    implementation(libs.opentelemetry.exporter.otlp)
}
