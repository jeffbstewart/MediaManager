plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

repositories {
    mavenCentral()
}

// JVM 17 target — matches the rest of the repo's lowest consumer.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

application {
    // Single entry point with a subcommand dispatcher inside Main.kt
    // so users invoke as:
    //   ./gradlew :app_store_demo_setup:run --args="fetch-movies $DEMO_MEDIA"
    // installDist also produces a `bin/app_store_demo_setup` wrapper
    // for users who prefer that to the gradle invocation.
    mainClass.set("net.stewart.mediamanager.demosetup.MainKt")
}

dependencies {
    // Gson is the project's shared JSON parser — already in the
    // version catalog, used by ProfileHttpService et al. Reusing
    // it here keeps dependency surface flat.
    implementation(libs.gson)

    // JDK's java.net.http.HttpClient covers our HTTP needs (no
    // pulling in OkHttp / Apache HC for a tool that runs once per
    // demo refresh).
}
