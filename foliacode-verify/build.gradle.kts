plugins {
    `java-library`
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    // No runtime dependencies on purpose. This module downloads and executes a third party
    // server jar, and the HTTP client and JSON reader it needs are small enough to keep in
    // the JDK. Adding libraries here would put them on the classpath of a process that also
    // loads arbitrary plugin code.

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    // Integration tests need the network and boot a real server, so they stay out of the
    // default build. Run them with: ./gradlew :foliacode-verify:integrationTest
    useJUnitPlatform {
        excludeTags("integration")
    }
    testLogging {
        events("passed", "skipped", "failed")
    }
}

tasks.register<Test>("integrationTest") {
    description = "Runs the tests that require network access and boot a real Folia server."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("integration")
    }
    // These are opt-in and slow; never let Gradle skip them because nothing changed.
    outputs.upToDateWhen { false }
}
