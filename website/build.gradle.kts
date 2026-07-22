import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.ZipFile
import org.gradle.api.GradleException
import org.gradle.language.base.plugins.LifecycleBasePlugin
import org.gradle.language.jvm.tasks.ProcessResources

plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    java
}

dependencyManagement {
    dependencies {
        dependency("net.bytebuddy:byte-buddy:1.18.11")
        dependency("net.bytebuddy:byte-buddy-agent:1.18.11")
    }
}

dependencies {
    implementation(project(":cbell-lib"))

    // Spring Boot dependencies
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-mongodb")
    implementation("org.springframework.boot:spring-boot-starter-json")
    implementation("org.springframework.boot:spring-boot-starter-logging")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-web")

    // Host metrics; Windows sensor binaries are pinned generated resources below.
    implementation("com.github.oshi:oshi-core:7.4.1")
    implementation("net.java.dev.jna:jna-platform-jpms:5.19.1")

    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.3")

    // Rate Limiting
    implementation("com.github.vladimir-bukhtoyarov:bucket4j-core:8.0.1")

    // Azure SDK
    implementation("com.azure:azure-data-tables:12.5.11")

    // JSoup
    implementation("org.jsoup:jsoup:1.22.2")

    // MapStruct
    implementation("org.mapstruct:mapstruct:1.6.3")
    annotationProcessor("org.mapstruct:mapstruct-processor:1.6.3")

    // Lombok
    compileOnly("org.projectlombok:lombok:1.18.46")
    annotationProcessor("org.projectlombok:lombok:1.18.46")
    annotationProcessor("org.projectlombok:lombok-mapstruct-binding:0.2.0")

    // Testing
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.security:spring-security-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testAnnotationProcessor("org.projectlombok:lombok:1.18.46")
    testCompileOnly("org.projectlombok:lombok:1.18.46")
}

springBoot {
    buildInfo()
}

val libreHardwareMonitorUri = URI(
    "https://github.com/LibreHardwareMonitor/LibreHardwareMonitor/releases/download/v0.9.6/LibreHardwareMonitor.zip")
val libreHardwareMonitorArchiveSha256 =
    "086d9f1b5a99e643edc2cfaaac16051685b551e4c5ac0b32a57c58c0e529c001"
val libreHardwareMonitorFiles = linkedMapOf(
    "LibreHardwareMonitorLib.dll" to "6ebc194316536ba61af5be24508ad9fcbb2ecc685e716c12e787c79530f66bf0",
    "HidSharp.dll" to "d86690efde30ea9179f669320f39148853793b743a98b531afeaf30598e22f54",
    "BlackSharp.Core.dll" to "cafb93afcc8d8a367e21f619673d05c06887d8964867fed1371f02ded1cd3e23",
    "DiskInfoToolkit.dll" to "1acbf51b3c10c51c986cf43021680d34a2e38d9a5ba652bcfa9a1b5f7fc09800",
    "RAMSPDToolkit-NDD.dll" to "b6882354c7c8ec186617e421507743dbfae09c5c1fc24cef76a1d0c0c26651de",
    "System.Memory.dll" to "d5e8e4866f9cfa66f7765660f84b210198893e55335487afe5ebda342c0e913d",
    "System.Runtime.CompilerServices.Unsafe.dll" to "08cbd7278b66f1e68425a82d4b97181a4130d93e3dd91831407aba7212ccdacf")

fun sha256(path: java.nio.file.Path): String = Files.newInputStream(path).use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    HexFormat.of().formatHex(digest.digest())
}

val sensorResourceDirectory = layout.buildDirectory.dir("generated/sensor-resources")
val prepareSensorResources by tasks.registering {
    val archive = layout.buildDirectory.file("sensor-downloads/LibreHardwareMonitor-0.9.6.zip")
    outputs.dir(sensorResourceDirectory)
    doLast {
        val archivePath = archive.get().asFile.toPath()
        Files.createDirectories(archivePath.parent)
        libreHardwareMonitorUri.toURL().openStream().use { input ->
            Files.copy(input, archivePath, StandardCopyOption.REPLACE_EXISTING)
        }
        if (sha256(archivePath) != libreHardwareMonitorArchiveSha256) {
            Files.deleteIfExists(archivePath)
            throw GradleException("LibreHardwareMonitor archive SHA-256 verification failed.")
        }
        val output = sensorResourceDirectory.get().dir("lib").asFile.toPath()
        Files.createDirectories(output)
        ZipFile(archivePath.toFile()).use { zip ->
            libreHardwareMonitorFiles.forEach { (name, expected) ->
                val entry = zip.getEntry(name)
                    ?: throw GradleException("LibreHardwareMonitor release is missing $name.")
                val target = output.resolve(name)
                zip.getInputStream(entry).use { input ->
                    Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
                }
                if (sha256(target) != expected) {
                    Files.deleteIfExists(target)
                    throw GradleException(
                        "LibreHardwareMonitor resource SHA-256 verification failed: $name")
                }
            }
        }
    }
}

sourceSets.named("main") { resources.srcDir(sensorResourceDirectory) }
tasks.named<ProcessResources>("processResources") { dependsOn(prepareSensorResources) }

val jsTestFiles = fileTree("src/test/js") {
    include("*.test.js")
}

tasks.register<Exec>("jsTest") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Runs browser-side JavaScript tests with Node's built-in test runner."
    workingDir = rootProject.projectDir

    inputs.files(jsTestFiles)
    inputs.dir("src/main/resources/static/js")
    inputs.dir("src/main/resources/static/css")
    inputs.dir("src/main/resources/templates")

    val nodeExecutable = providers.environmentVariable("NODE_EXE").orElse("node")

    doFirst {
        val files = jsTestFiles.files.sortedBy { it.name }.map { it.absolutePath }
        if (files.isEmpty()) {
            throw GradleException("No JavaScript tests found under website/src/test/js.")
        }
        commandLine(listOf(nodeExecutable.get(), "--test") + files)
    }
}

val sharedFolderWorkerPesterFiles = rootProject.files(
    "ops/production/windows/tests/Production.SharedFolderWorker.Tests.ps1")
val sharedFolderOperationsPesterFiles = rootProject.files(
    "ops/production/windows/tests/Production.Install.Tests.ps1",
    "ops/production/windows/tests/Production.Operations.Tests.ps1")
val sharedFolderPesterInputs = rootProject.files(
    "ops/production/windows/modules",
    "ops/production/windows/service",
    "ops/production/windows/config")
val requiredPesterVersion = "5.9.0"
val pesterReportDirectory = layout.buildDirectory.dir("test-results/shared-folder-pester")
val pwshExecutable = providers.environmentVariable("PWSH_EXE")
    .orElse("C:\\Program Files\\PowerShell\\7\\pwsh.exe")
val windowsPowerShellExecutable = providers.environmentVariable("WINDOWS_POWERSHELL_EXE")
    .orElse("C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe")

fun quotedPowerShellPath(path: String): String = "'${path.replace("'", "''")}'"

fun pesterPaths(files: org.gradle.api.file.FileCollection): String = files.files
    .sortedBy { it.absolutePath }
    .joinToString(",") { quotedPowerShellPath(it.absolutePath) }

fun requireWindowsExecutable(executable: String, label: String): String {
    if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        throw GradleException(
            "Shared-folder Pester verification requires Windows; run it on the production-compatible Windows host.")
    }
    val candidate = file(executable)
    if (!candidate.isFile) {
        throw GradleException("$label was not found at ${candidate.absolutePath}.")
    }
    return candidate.absolutePath
}

val sharedFolderWorkerPester = tasks.register<Exec>("sharedFolderWorkerPester") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Runs shared-folder media-worker Pester coverage under PowerShell 7."
    workingDir = rootProject.projectDir
    inputs.files(sharedFolderWorkerPesterFiles, sharedFolderPesterInputs)
    val report = pesterReportDirectory.map { it.file("worker-pwsh7.xml") }
    outputs.file(report)
    outputs.upToDateWhen { false }

    doFirst {
        val executable = requireWindowsExecutable(pwshExecutable.get(), "PowerShell 7")
        val reportFile = report.get().asFile
        reportFile.parentFile.mkdirs()
        val command = """
            ${'$'}ErrorActionPreference = 'Stop'
            if (${'$'}PSVersionTable.PSVersion.Major -lt 7) { throw 'PowerShell 7 or newer is required.' }
            try {
                Import-Module Pester -RequiredVersion $requiredPesterVersion -ErrorAction Stop
            } catch {
                throw 'Required Pester $requiredPesterVersion module is unavailable.'
            }
            if ((Get-Module Pester).Version -ne [version]'$requiredPesterVersion') {
                throw 'Required Pester $requiredPesterVersion module failed to load.'
            }
            ${'$'}configuration = New-PesterConfiguration
            ${'$'}configuration.Run.Path = @(${pesterPaths(sharedFolderWorkerPesterFiles)})
            ${'$'}configuration.Run.Exit = ${'$'}true
            ${'$'}configuration.Output.Verbosity = 'Detailed'
            ${'$'}configuration.TestResult.Enabled = ${'$'}true
            ${'$'}configuration.TestResult.OutputFormat = 'NUnitXml'
            ${'$'}configuration.TestResult.OutputPath = ${quotedPowerShellPath(reportFile.absolutePath)}
            Invoke-Pester -Configuration ${'$'}configuration
        """.trimIndent()
        commandLine(executable, "-NoLogo", "-NoProfile", "-Command", command)
    }
}

val sharedFolderOperationsPwshPester = tasks.register<Exec>("sharedFolderOperationsPwshPester") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Runs shared-folder installer and operations Pester coverage under PowerShell 7."
    workingDir = rootProject.projectDir
    inputs.files(sharedFolderOperationsPesterFiles, sharedFolderPesterInputs)
    val report = pesterReportDirectory.map { it.file("operations-pwsh7.xml") }
    outputs.file(report)
    outputs.upToDateWhen { false }

    doFirst {
        val executable = requireWindowsExecutable(pwshExecutable.get(), "PowerShell 7")
        val reportFile = report.get().asFile
        reportFile.parentFile.mkdirs()
        val command = """
            ${'$'}ErrorActionPreference = 'Stop'
            if (${'$'}PSVersionTable.PSVersion.Major -lt 7) { throw 'PowerShell 7 or newer is required.' }
            try {
                Import-Module Pester -RequiredVersion $requiredPesterVersion -ErrorAction Stop
            } catch {
                throw 'Required Pester $requiredPesterVersion module is unavailable.'
            }
            if ((Get-Module Pester).Version -ne [version]'$requiredPesterVersion') {
                throw 'Required Pester $requiredPesterVersion module failed to load.'
            }
            ${'$'}configuration = New-PesterConfiguration
            ${'$'}configuration.Run.Path = @(${pesterPaths(sharedFolderOperationsPesterFiles)})
            ${'$'}configuration.Run.Exit = ${'$'}true
            ${'$'}configuration.Output.Verbosity = 'Detailed'
            ${'$'}configuration.TestResult.Enabled = ${'$'}true
            ${'$'}configuration.TestResult.OutputFormat = 'NUnitXml'
            ${'$'}configuration.TestResult.OutputPath = ${quotedPowerShellPath(reportFile.absolutePath)}
            Invoke-Pester -Configuration ${'$'}configuration
        """.trimIndent()
        commandLine(executable, "-NoLogo", "-NoProfile", "-Command", command)
    }
}

val sharedFolderOperationsWindowsPowerShellPester =
    tasks.register<Exec>("sharedFolderOperationsWindowsPowerShellPester") {
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        description = "Runs installer and operations compatibility coverage under Windows PowerShell 5.1."
        workingDir = rootProject.projectDir
        inputs.files(sharedFolderOperationsPesterFiles, sharedFolderPesterInputs)
        val report = pesterReportDirectory.map { it.file("operations-powershell51.xml") }
        outputs.file(report)
        outputs.upToDateWhen { false }

        doFirst {
            val executable = requireWindowsExecutable(
                windowsPowerShellExecutable.get(), "Windows PowerShell 5.1")
            val reportFile = report.get().asFile
            reportFile.parentFile.mkdirs()
            val command = """
                ${'$'}ErrorActionPreference = 'Stop'
                if (${'$'}PSVersionTable.PSVersion.Major -ne 5 -or
                    ${'$'}PSVersionTable.PSVersion.Minor -lt 1) {
                    throw 'Windows PowerShell 5.1 is required.'
                }
                ${'$'}documents = [Environment]::GetFolderPath([Environment+SpecialFolder]::MyDocuments)
                ${'$'}coreModules = Join-Path ${'$'}documents 'PowerShell\Modules'
                ${'$'}windowsUserModules = Join-Path ${'$'}documents 'WindowsPowerShell\Modules'
                ${'$'}programModules = Join-Path ${'$'}env:ProgramFiles 'WindowsPowerShell\Modules'
                ${'$'}builtInModules = Join-Path ${'$'}env:WINDIR 'System32\WindowsPowerShell\v1.0\Modules'
                ${'$'}env:PSModulePath = @(
                    ${'$'}coreModules,
                    ${'$'}windowsUserModules,
                    ${'$'}programModules,
                    ${'$'}builtInModules) -join ';'
                try {
                    Import-Module Pester -RequiredVersion $requiredPesterVersion -ErrorAction Stop
                } catch {
                    throw 'Required Pester $requiredPesterVersion module is unavailable.'
                }
                if ((Get-Module Pester).Version -ne [version]'$requiredPesterVersion') {
                    throw 'Required Pester $requiredPesterVersion module failed to load.'
                }
                ${'$'}configuration = New-PesterConfiguration
                ${'$'}configuration.Run.Path = @(${pesterPaths(sharedFolderOperationsPesterFiles)})
                ${'$'}configuration.Run.Exit = ${'$'}true
                ${'$'}configuration.Output.Verbosity = 'Detailed'
                ${'$'}configuration.TestResult.Enabled = ${'$'}true
                ${'$'}configuration.TestResult.OutputFormat = 'NUnitXml'
                ${'$'}configuration.TestResult.OutputPath = ${quotedPowerShellPath(reportFile.absolutePath)}
                Invoke-Pester -Configuration ${'$'}configuration
            """.trimIndent()
            commandLine(executable, "-NoLogo", "-NoProfile", "-Command", command)
        }
    }

tasks.register("sharedFolderVerification") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Runs shared-folder Java, browser, worker, and operations regression coverage."
    dependsOn(
        tasks.named("test"),
        tasks.named("jsTest"),
        sharedFolderWorkerPester,
        sharedFolderOperationsPwshPester,
        sharedFolderOperationsWindowsPowerShellPester)
}

tasks.named("check") {
    dependsOn("jsTest")
}

val verifySensorRuntime by tasks.registering {
    dependsOn(tasks.named("bootJar"))
    doLast {
        val jar = tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar")
            .get().archiveFile.get().asFile
        ZipFile(jar).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            if (names.any { it.contains("jLibreHardwareMonitor", ignoreCase = true) ||
                    it.contains("WinRing", ignoreCase = true) }) {
                throw GradleException("Legacy WinRing0 sensor runtime is present in the boot JAR.")
            }
            libreHardwareMonitorFiles.keys.forEach { name ->
                if ("BOOT-INF/classes/lib/$name" !in names) {
                    throw GradleException("Pinned sensor runtime is missing from the boot JAR: $name")
                }
            }
        }
    }
}
tasks.named("check") { dependsOn(verifySensorRuntime) }
