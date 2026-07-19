plugins {
    java
    application
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
    implementation(project(":foliacode-core"))
    implementation(project(":foliacode-agent"))
    implementation(project(":foliacode-transform"))
    implementation(project(":foliacode-verify"))
    implementation(libs.snakeyaml)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(testFixtures(project(":foliacode-core")))
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass = "dev.marv.foliacode.cli.Main"
}

/** Runnable JAR with every dependency bundled in. */
tasks.register<Jar>("fatJar") {
    group = "build"
    description = "Builds a runnable JAR with every dependency bundled in"

    // The runtime classpath includes the sibling modules' jars. Without this,
    // Gradle does not know :foliacode-core:jar and :foliacode-verify:jar have to
    // run first, and a clean build can package stale or missing output.
    dependsOn(configurations.runtimeClasspath)

    archiveClassifier = "all"
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "dev.marv.foliacode.cli.Main"
        attributes["Implementation-Version"] = project.version

        // The same JAR is both the CLI and the runtime agent. Attaching the agent with
        // -javaagent needs a JAR with Premain-Class in its manifest, and pointing the
        // user at a second artifact they have to keep in step with this one is a way to
        // end up with a mismatched pair.
        attributes["Premain-Class"] = "dev.marv.foliacode.agent.FoliaCodeAgent"
        attributes["Agent-Class"] = "dev.marv.foliacode.agent.FoliaCodeAgent"
        attributes["Can-Retransform-Classes"] = "false"
    }
    from(sourceSets.main.get().output)
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/MANIFEST.MF")
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
