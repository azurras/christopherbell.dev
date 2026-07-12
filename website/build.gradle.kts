import org.gradle.api.GradleException
import org.gradle.language.base.plugins.LifecycleBasePlugin

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

    // Host metrics and Windows hardware sensors
    implementation("com.github.oshi:oshi-core:7.4.0")
    implementation("io.github.pandalxb:jLibreHardwareMonitor:1.0.6")

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

tasks.named("check") {
    dependsOn("jsTest")
}
