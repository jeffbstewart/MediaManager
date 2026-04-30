plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.owasp.depcheck)
    alias(libs.plugins.protobuf)
    application
    jacoco
}

jacoco {
    toolVersion = "0.8.13"
}

/**
 * Compute the JaCoCo class-include globs from the authored Kotlin
 * sources under `src/main/kotlin/`. Each `.kt` file maps to the
 * top-level class (`<base>.class`), its nested / synthetic classes
 * (`<base>$*.class`), the file-level facade Kotlin generates for
 * top-level functions (`<base>Kt.class`), and that facade's nested
 * classes (`<base>Kt$*.class`).
 *
 * The point: protobuf-generated `<message>.java` and
 * `<message>Kt.kt` files live alongside authored ones in the same
 * `net/stewart/mediamanager/grpc/` package after compilation, so a
 * blanket directory exclude won't separate them. Driving the
 * include list off the authored source set is the only stable
 * heuristic.
 */
fun authoredClassIncludes(): List<String> {
    val srcRoot = file("src/main/kotlin")
    if (!srcRoot.isDirectory) return emptyList()
    val patterns = mutableListOf<String>()
    srcRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { kt ->
        val rel = kt.relativeTo(srcRoot).invariantSeparatorsPath
        val pkgPath = rel.substringBeforeLast('/', "")
        val base = rel.substringAfterLast('/').removeSuffix(".kt")
        val prefix = if (pkgPath.isEmpty()) "" else "$pkgPath/"
        patterns += "$prefix$base.class"
        patterns += "$prefix$base\$*.class"
        patterns += "$prefix${base}Kt.class"
        patterns += "$prefix${base}Kt\$*.class"
    }
    return patterns
}

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.named("test"))
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }
    // Drive the coverage scope off the authored Kotlin sources. This
    // pulls authored Kotlin classes (and their nested classes /
    // facades) out of the build/classes tree while leaving protoc-
    // generated Java + Kotlin DSL wrappers behind. Anything not
    // matched is dropped from the denominator and the report.
    val includes = authoredClassIncludes()
    classDirectories.setFrom(
        files(classDirectories.files.map {
            fileTree(it) { include(includes) }
        })
    )
}

/**
 * Plain-text coverage summary from the JaCoCo XML report. Designed to
 * be low-token: prints overall %s for INSTRUCTION / LINE / BRANCH /
 * METHOD / CLASS, a per-package breakdown sorted by line coverage,
 * and the N least-covered classes (default 25). Avoids the giant HTML
 * report so callers can grep / read inline.
 *
 * Usage:
 *   ./gradlew jacocoTestReport coverageSummary
 *   ./gradlew coverageSummary -Plimit=50    # show 50 worst classes
 *   ./gradlew coverageSummary -Ppackage=net/stewart/mediamanager/service
 *
 * Implemented inline so the configuration cache doesn't need to
 * serialise script-level helper functions.
 */
tasks.register("coverageSummary") {
    group = "verification"
    description = "Print a plain-text summary of the JaCoCo coverage report."
    dependsOn("jacocoTestReport")
    val reportFile = layout.buildDirectory.file("reports/jacoco/test/jacocoTestReport.xml")
    inputs.file(reportFile)
    val limitProp = providers.gradleProperty("limit").orElse("25")
    val packageFilterProp = providers.gradleProperty("package").orElse("")
    val taskLogger = logger
    doLast {
        val xmlFile = reportFile.get().asFile
        if (!xmlFile.exists()) {
            error("Coverage XML not found at ${xmlFile.path}. Run jacocoTestReport first.")
        }
        val limit = limitProp.get().toInt()
        val packageFilter = packageFilterProp.get().takeIf { it.isNotBlank() }

        // --- Parse the JaCoCo XML. ---
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isValidating = false
        }
        val doc = factory.newDocumentBuilder().parse(xmlFile)
        val root = doc.documentElement

        // (missed, covered) pair for one of JaCoCo's 5 counter types.
        data class Counter(val missed: Long, val covered: Long) {
            val total: Long get() = missed + covered
            val pct: Double get() = if (total == 0L) 0.0 else covered.toDouble() * 100.0 / total
            operator fun plus(other: Counter) = Counter(missed + other.missed, covered + other.covered)
        }

        fun directChildren(el: org.w3c.dom.Element, tag: String): List<org.w3c.dom.Element> {
            val out = mutableListOf<org.w3c.dom.Element>()
            val children = el.childNodes
            for (i in 0 until children.length) {
                val n = children.item(i)
                if (n is org.w3c.dom.Element && n.tagName == tag) out += n
            }
            return out
        }
        fun directCounter(el: org.w3c.dom.Element, type: String): Counter? {
            for (n in directChildren(el, "counter")) {
                if (n.getAttribute("type") == type) {
                    return Counter(n.getAttribute("missed").toLong(), n.getAttribute("covered").toLong())
                }
            }
            return null
        }

        val zero = Counter(0L, 0L)
        val overallTypes = listOf("INSTRUCTION", "LINE", "BRANCH", "METHOD", "CLASS")
        val overall = overallTypes.associateWith { directCounter(root, it) ?: zero }

        data class Pkg(val name: String, val line: Counter, val branch: Counter, val instr: Counter)
        data class ClassRow(val pkg: String, val name: String, val line: Counter)

        val packages = mutableListOf<Pkg>()
        val classes = mutableListOf<ClassRow>()

        directChildren(root, "package").forEach { pkgEl ->
            val pkgName = pkgEl.getAttribute("name")
            if (packageFilter != null && !pkgName.startsWith(packageFilter)) return@forEach
            val line = directCounter(pkgEl, "LINE") ?: zero
            val branch = directCounter(pkgEl, "BRANCH") ?: zero
            val instr = directCounter(pkgEl, "INSTRUCTION") ?: zero
            packages += Pkg(pkgName, line, branch, instr)
            directChildren(pkgEl, "class").forEach { cls ->
                val raw = cls.getAttribute("name")
                val short = raw.substringAfterLast('/').substringBefore('$')
                val cline = directCounter(cls, "LINE") ?: zero
                if (cline.total == 0L) return@forEach
                classes += ClassRow(pkgName, short, cline)
            }
        }

        fun fmtPct(c: Counter): String = "%5.1f%%".format(c.pct)
        fun fmtCount(c: Counter): String = "%6d/%-6d".format(c.covered, c.total)

        val out = StringBuilder()
        out.appendLine("=== Coverage summary (JaCoCo) ===")
        if (packageFilter != null) out.appendLine("filter: package starts-with '$packageFilter'")
        out.appendLine()
        out.appendLine("Overall:")
        out.appendLine("  L ${fmtPct(overall["LINE"]!!)} (${fmtCount(overall["LINE"]!!)})   " +
            "B ${fmtPct(overall["BRANCH"]!!)} (${fmtCount(overall["BRANCH"]!!)})   " +
            "I ${fmtPct(overall["INSTRUCTION"]!!)} (${fmtCount(overall["INSTRUCTION"]!!)})")
        out.appendLine("  M ${fmtPct(overall["METHOD"]!!)} (${fmtCount(overall["METHOD"]!!)})   " +
            "C ${fmtPct(overall["CLASS"]!!)} (${fmtCount(overall["CLASS"]!!)})")
        out.appendLine()

        out.appendLine("Per package (line %, sorted worst-first):")
        val pkgWidth = packages.maxOfOrNull { it.name.length } ?: 0
        packages.sortedBy { it.line.pct }.forEach { p ->
            out.appendLine("  %-${pkgWidth}s   L %5.1f%% (%6d/%-6d)   B %5.1f%% (%6d/%-6d)".format(
                p.name, p.line.pct, p.line.covered, p.line.total,
                p.branch.pct, p.branch.covered, p.branch.total,
            ))
        }
        out.appendLine()

        out.appendLine("Lowest-covered classes (top $limit by missed lines):")
        val classWidth = classes.maxOfOrNull { it.name.length } ?: 0
        classes
            .sortedWith(compareByDescending<ClassRow> { it.line.missed }.thenBy { it.line.pct })
            .take(limit)
            .forEach { c ->
                out.appendLine("  %-${classWidth}s   L %5.1f%% (%6d/%-6d)   pkg=%s".format(
                    c.name, c.line.pct, c.line.covered, c.line.total, c.pkg,
                ))
            }

        out.toString().lineSequence().forEach { taskLogger.lifecycle(it) }
    }
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

    // gRPC + Protobuf (Armeria provides the HTTP/2 transport for our
    // inbound gRPC server; grpc-netty-shaded below is the OUTBOUND
    // transport ManagedChannelBuilder needs for MadmomClient.)
    implementation(libs.grpc.stub)
    implementation(libs.grpc.kotlin.stub)
    implementation(libs.grpc.protobuf)
    implementation(libs.armeria.grpc)
    implementation(libs.grpc.netty.shaded)
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
    val override = (project.findProperty("maxForks") as? String)?.toIntOrNull()
    maxParallelForks = override
        ?: (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(2)
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

// Web-app TypeScript codegen from .proto files. Same source of truth as
// the Kotlin/iOS/Android-TV codegen — a single field add in proto/ flows
// to all four clients. Output (web-app/src/app/proto-gen/) is gitignored.
//
// This task is convenience for local dev: run `./gradlew generateWebProto`
// after editing a .proto and the SPA's TypeScript types refresh in one
// shot. The "never-forget" guarantee comes from elsewhere:
//   - npm `prebuild` / `prestart` hooks regenerate before `ng build` /
//     `ng serve`, so the SPA's bundle is always built against fresh
//     types (the Docker angular-builder stage uses this path).
//   - lifecycle/pre-submit.sh runs gen-proto.mjs before the Playwright
//     suite, so a stale dir can't pass tests.
//
// Skipped when run from a context that doesn't have web-app/ on disk
// (e.g. the Kotlin-only Docker stage that copies `src/` and `proto/`
// but not `web-app/`).
tasks.register<Exec>("generateWebProto") {
    description = "Generate TypeScript types from .proto files for the Angular web-app"
    group = "build"
    workingDir = file("web-app")
    val npm = if (org.gradle.internal.os.OperatingSystem.current().isWindows) "npm.cmd" else "npm"
    commandLine(npm, "run", "proto:gen")

    val genScript = file("web-app/tests/scripts/gen-proto.mjs")
    val packageJson = file("web-app/package.json")
    onlyIf("web-app/ is present") { genScript.exists() && packageJson.exists() }

    inputs.dir("proto").withPropertyName("protoSources")
    inputs.files(genScript).withPropertyName("genScript").optional()
    inputs.files(packageJson).withPropertyName("packageJson").optional()
    outputs.dir("web-app/src/app/proto-gen").withPropertyName("generatedTs")
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
