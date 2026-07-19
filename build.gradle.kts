plugins {
    java
}

allprojects {
    group = "dev.marv.foliacode"
    version = "0.1.0"
}

subprojects {
    apply(plugin = "maven-publish")

    // Each module decides for itself whether it is a Java project; this only reacts once
    // one of them says so, rather than forcing the plugin on from above.
    plugins.withId("java") {
        extensions.configure<JavaPluginExtension> {
            withSourcesJar()
            withJavadocJar()
        }

        // The javadoc here is written to explain why the code is shaped the way it is,
        // not to satisfy a checker. Doclint would reject prose that reads perfectly well
        // and turn a publish into a formatting argument.
        tasks.withType<Javadoc>().configureEach {
            (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
        }

        extensions.configure<PublishingExtension> {
            publications {
                create<MavenPublication>("maven") {
                    from(components["java"])
                    pom {
                        name = project.name
                        description = "Folia compatibility analyzer for Bukkit plugins"
                        url = "https://github.com/MARVserver/Foliacode"
                        licenses {
                            license {
                                name = "MIT License"
                                url = "https://opensource.org/licenses/MIT"
                            }
                        }
                        scm {
                            url = "https://github.com/MARVserver/Foliacode"
                            connection = "scm:git:https://github.com/MARVserver/Foliacode.git"
                        }
                    }
                }
            }

            repositories {
                maven {
                    name = "GitHubPackages"
                    url = uri("https://maven.pkg.github.com/MARVserver/Foliacode")
                    // Supplied by the release workflow. Absent locally, which makes
                    // `publish` fail loudly rather than publish from somebody's laptop.
                    credentials {
                        username = providers.gradleProperty("gpr.user")
                            .orElse(providers.environmentVariable("GITHUB_ACTOR"))
                            .orNull
                        password = providers.gradleProperty("gpr.token")
                            .orElse(providers.environmentVariable("GITHUB_TOKEN"))
                            .orNull
                    }
                }
            }
        }
    }
}
