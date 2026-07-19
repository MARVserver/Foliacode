plugins {
    `java-library`
    // Test helpers (javac invocation, JAR assembly) are shared with the CLI module tests
    `java-test-fixtures`
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
    api(libs.asm)
    api(libs.asm.tree)
    implementation(libs.asm.util)
    implementation(libs.snakeyaml)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
