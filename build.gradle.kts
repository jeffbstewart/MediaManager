plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.owasp.depcheck)
    alias(libs.plugins.protobuf)
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

dependencies {
    implementation(libs.vok.db)
    implementation(libs.hikaricp)
    implementation(libs.h2)
    implementation(libs.flyway.core)
    implementation(libs.slf4j.api)
    implementation(libs.jbcrypt)
    implementation(libs.micrometer.prometheus)
    implementation(libs.gson)
    implementation(libs.java.jwt)
    implementation(libs.webauthn4j.core)
    implementation(libs.openpdf)
    implementation(libs.zxing.core)
    implementation(libs.zxing.javase)
    implementation(project(":transcode-common"))

    // gRPC + Protobuf (Armeria provides the HTTP/2 transport)
    implementation(libs.grpc.stub)
    implementation(libs.grpc.kotlin.stub)
    implementation(libs.grpc.protobuf)
    implementation(libs.armeria.grpc)
    implementation(libs.protobuf.kotlin)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.grpc.inprocess)
    testImplementation(libs.grpc.testing)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

sourceSets {
    main {
        proto {
            srcDir("proto")
        }
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:${libs.versions.grpc.get()}"
        }
        create("grpckt") {
            artifact = "io.grpc:protoc-gen-grpc-kotlin:1.4.1:jdk8@jar"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("kotlin")
            }
            task.plugins {
                create("grpc")
                create("grpckt")
            }
        }
    }
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
