plugins {
    id("org.springframework.boot") version "4.1.2" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    java
}

fun validatedReleaseVersion(raw: String): String {
    val value = raw.trim()
    if (raw != value || !value.matches(Regex("[0-9A-Za-z][0-9A-Za-z._+-]{0,127}"))) {
        throw GradleException(
            "releaseVersion must be 1-128 letters, digits, dots, underscores, pluses, or hyphens.")
    }
    return value
}

fun developmentVersion(commit: String): String {
    val normalized = commit.trim().lowercase()
    if (!normalized.matches(Regex("[0-9a-f]{40}"))) {
        throw GradleException("Git HEAD must resolve to a full 40-character commit SHA.")
    }
    return "0.0.0-dev.$normalized"
}

val sourceGitCommit = providers.exec {
    commandLine("git", "rev-parse", "HEAD")
    workingDir(rootProject.projectDir)
}.standardOutput.asText.map(String::trim)
val explicitReleaseVersion = providers.gradleProperty("releaseVersion")
    .orElse(providers.environmentVariable("RELEASE_VERSION"))
val resolvedVersion = explicitReleaseVersion.map(::validatedReleaseVersion)
    .orElse(sourceGitCommit.map(::developmentVersion))

group = "dev.christopherbell"
version = resolvedVersion.get()

tasks.register("verifyDeterministicVersion") {
    group = "verification"
    description = "Verifies that artifact versioning is independent of clock and build order."
    inputs.property("resolvedVersion", resolvedVersion)
    inputs.property("sourceGitCommit", sourceGitCommit)

    doLast {
        val commit = sourceGitCommit.get()
        val firstResolution = developmentVersion(commit)
        val repeatedResolution = developmentVersion(commit)
        check(firstResolution == repeatedResolution) {
            "Repeated development-version resolution changed for one commit."
        }
        if (!explicitReleaseVersion.isPresent) {
            check(project.version.toString() == firstResolution) {
                "Development version must contain the exact source commit."
            }
        }
    }
}

tasks.named("check") {
    dependsOn("verifyDeterministicVersion")
}

subprojects {
    repositories {
        mavenCentral()
    }

    // Spring Boot 4.1.1 manages versions with published advisories: Jackson parsing denial of
    // service (CVE-2026-89425, CVE-2026-89407, CVE-2026-91777, CVE-2026-91776, CVE-2026-68497)
    // and Tomcat access control (CVE-2026-65182, CVE-2026-65905, CVE-2026-68525). Drop these
    // overrides once a Boot release manages these versions or newer.
    extra["jackson-bom.version"] = "3.1.7"
    extra["jackson-2-bom.version"] = "2.21.7"
    extra["tomcat.version"] = "11.0.26"

    plugins.withType<JavaPlugin> {
        // The mechanical Chris Street Style rules; config/checkstyle/suppressions.xml lists
        // packages whose migration slice has not merged yet.
        apply(plugin = "checkstyle")
        extensions.configure<CheckstyleExtension> {
            toolVersion = "14.3.0"
            configDirectory.set(rootProject.layout.projectDirectory.dir("config/checkstyle"))
            maxWarnings = 0
        }
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_25
            targetCompatibility = JavaVersion.VERSION_25
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(25))
            }
        }
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-parameters")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        jvmArgs("--enable-native-access=ALL-UNNAMED")
        testLogging {
            events("passed", "skipped", "failed")
        }
    }
}
