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
    // vok-framework-vokdb is used only for its jdbi-orm wrapper. Vaadin was
    // removed when we migrated the UI to Angular, so exclude the whole
    // com.vaadin transitive tree — this drops vaadin-core, vaadin-dev, and
    // copilot, along with their vulnerable Netty 4.1.116 and
    // commons-fileupload2 2.0.0-M1 dependencies.
    implementation(libs.vok.db) {
        exclude(group = "com.vaadin")
    }
    implementation(libs.hikaricp)
    implementation(libs.h2)
    implementation(libs.flyway.core)
    implementation(project(":logging-common"))
    implementation(libs.jbcrypt)
    implementation(libs.micrometer.prometheus)
    implementation(libs.gson)
    implementation(libs.java.jwt)
    implementation(libs.webauthn4j.core)
    implementation(libs.openpdf)
    implementation(libs.pdfbox)
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

    // Security-driven transitive version pins. Each line below fixes a CVE
    // reported by OWASP dependency-check; drop a pin once the root dep that
    // pulls in the vulnerable version is itself upgraded past the fix.
    constraints {
        implementation("org.apache.commons:commons-lang3:3.20.0") {
            because("CVE-2025-48924: ClassUtils.getClass uncontrolled recursion (fixed in 3.18.0)")
        }
    }
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
            artifact = "io.grpc:protoc-gen-grpc-kotlin:1.5.0:jdk8@jar"
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

// OWASP dependency-check: fail on HIGH+ CVEs (CVSS >= 7.0). Lower-severity
// findings are still reported but don't break the build. Node/npm analyzers are
// disabled here — the Angular web-app is scanned separately via `npm audit`.
dependencyCheck {
    failBuildOnCVSS = 7.0f
    failOnError = false
    suppressionFile = "owasp-suppressions.xml"
    analyzers.apply {
        nodeEnabled = false
        nodeAuditEnabled = false
        ossIndexEnabled = false
    }
}

// npm audit for the Angular web-app. OWASP dependency-check's built-in node
// analyzers query the same GitHub Advisory data less reliably; running npm's
// own audit is authoritative for npm packages. --audit-level=high matches the
// CVSS 7.0 threshold used for Java/Kotlin deps above.
tasks.register<Exec>("npmAudit") {
    description = "Run `npm audit --audit-level=high` in web-app/"
    group = "verification"
    workingDir = file("web-app")
    val npm = if (org.gradle.internal.os.OperatingSystem.current().isWindows) "npm.cmd" else "npm"
    commandLine(npm, "audit", "--audit-level=high")
}

tasks.register("recordDepScan") {
    description = "Run OWASP (Java/Kotlin) + npm (Angular) dependency audits and record the scan timestamp"
    dependsOn("dependencyCheckAnalyze", "npmAudit")
    notCompatibleWithConfigurationCache("OWASP dependency-check uses Task.project at execution time")
    doLast {
        val sentinel = File("data/last-dep-scan")
        sentinel.parentFile.mkdirs()
        sentinel.createNewFile()
        sentinel.setLastModified(System.currentTimeMillis())
    }
}
