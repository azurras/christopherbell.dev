import java.time.LocalDate
import java.time.format.DateTimeFormatter

plugins {
    id("org.springframework.boot") version "4.1.0" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    java
}

group = "dev.christopherbell"
val buildNumber = System.getenv("BUILD_NUMBER") ?: "0"
val dateVersion = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy.MM.dd"))
version = "$dateVersion.$buildNumber"

subprojects {
    repositories {
        mavenCentral()
    }

    plugins.withType<JavaPlugin> {
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
        testLogging {
            events("passed", "skipped", "failed")
        }
    }
}
