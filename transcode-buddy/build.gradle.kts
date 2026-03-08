plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

application {
    mainClass.set("net.stewart.transcodebuddy.MainKt")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":transcode-common"))
    implementation(libs.gson)
    implementation(libs.slf4j.simple)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
