plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.vaadin)
    alias(libs.plugins.owasp.depcheck)
    application
}

group = "net.stewart"
version = "0.1.0"

application {
    mainClass.set("net.stewart.mediamanager.MainKt")
}

repositories {
    mavenCentral()
}

configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group.startsWith("org.eclipse.jetty")) {
            useVersion("12.1.6")
            because("CVE-2026-1605: GzipHandler memory leak in Jetty <12.1.6")
        }
    }
}

dependencies {
    implementation(libs.vaadin.core)
    if (!vaadin.effective.productionMode.get()) {
        implementation(libs.vaadin.dev)
    }
    implementation(libs.vaadin.boot)
    implementation(libs.karibu.dsl)
    implementation(libs.vok.db)
    implementation(libs.hikaricp)
    implementation(libs.h2)
    implementation(libs.flyway.core)
    implementation(libs.slf4j.api)
    implementation(libs.jbcrypt)
    implementation(libs.micrometer.prometheus)
    implementation(libs.gson)
    implementation(libs.java.jwt)
    implementation(libs.openpdf)
    implementation(libs.zxing.core)
    implementation(libs.zxing.javase)
    implementation(project(":transcode-common"))

    testImplementation(libs.karibu.testing)
    testImplementation(libs.kotlin.test.junit)
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

tasks.withType<Test>().configureEach {
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(2)
}

// OWASP dependency-check: report all CVEs but don't fail the build
dependencyCheck {
    failBuildOnCVSS = 0f
    failOnError = false
    suppressionFile = "owasp-suppressions.xml"
    analyzers.apply {
        nodeEnabled = false
        nodeAuditEnabled = false
        ossIndexEnabled = false
    }
}

tasks.register("recordDepScan") {
    description = "Run OWASP dependency-check and record the scan timestamp"
    dependsOn("dependencyCheckAnalyze")
    notCompatibleWithConfigurationCache("OWASP dependency-check uses Task.project at execution time")
    doLast {
        val sentinel = File("data/last-dep-scan")
        sentinel.parentFile.mkdirs()
        sentinel.createNewFile()
        sentinel.setLastModified(System.currentTimeMillis())
    }
}

// In production mode, ensure vaadinBuildFrontend runs before run and jar
if (vaadin.effective.productionMode.get()) {
    tasks.named("run") {
        dependsOn("vaadinBuildFrontend")
    }
    tasks.named("jar") {
        dependsOn("vaadinBuildFrontend")
    }
}

