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
    // The registry of unsafe APIs is the same knowledge the static analyzer uses. Duplicating
    // it here would let the two drift apart, and a runtime report that disagrees with the
    // static one about what counts as unsafe would be worse than no runtime report at all.
    api(project(":foliacode-core"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(testFixtures(project(":foliacode-core")))
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
