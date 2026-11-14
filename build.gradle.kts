/*
 * SPDX-FileCopyrightText: 2026 Progmasoft <support@progmasoft.com>
 * SPDX-License-Identifier: MPL-2.0 WITH AdditionRef-Progmasoft-Exception-2.0
 */

plugins {
    base
    id("com.diffplug.spotless") version "8.11.0"
}

val etincelleVersion: String by project

allprojects {
    group = "com.progmasoft.etincelle"
    version = etincelleVersion
    repositories { mavenCentral() }
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "jacoco")
    apply(plugin = "maven-publish")
    apply(plugin = "com.diffplug.spotless")

    repositories { mavenCentral() }

    extensions.configure<JavaPluginExtension> {
        withSourcesJar()
        withJavadocJar()
    }

    // Java 21 is the oldest Java Etincelle runs on: compiled against its API
    // whatever JDK builds it, and tested on 21 and on the newest LTS in CI.
    tasks.withType<JavaCompile>().configureEach {
        options.release = 21
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
    }

    tasks.withType<Javadoc>().configureEach {
        (options as StandardJavadocDocletOptions).addBooleanOption("Xdoclint:all,-missing", true)
    }

    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:6.1.3"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    extensions.configure<JacocoPluginExtension> { toolVersion = "0.8.16" }

    tasks.named<JacocoReport>("jacocoTestReport") {
        dependsOn(tasks.named("test"))
        reports {
            xml.required = true
            html.required = false
        }
    }

    tasks.named<Jar>("jar") {
        manifest {
            attributes(
                "Automatic-Module-Name" to "com.progmasoft." + project.name.substringBefore('_').replace('-', '.'),
                "Implementation-Title" to project.name,
                "Implementation-Version" to project.version,
            )
        }
    }

    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }

    extensions.configure<PublishingExtension> {
        publications {
            create<MavenPublication>("maven") {
                from(components["java"])
                artifactId = project.name
                pom {
                    name = project.name
                    url = "https://github.com/Progmasoft/etincelle"
                    licenses {
                        license {
                            name = "MPL-2.0 WITH AdditionRef-Progmasoft-Exception-2.0"
                            url = "https://github.com/Progmasoft/etincelle/blob/main/LICENSE.txt"
                        }
                    }
                }
            }
        }
        repositories {
            maven {
                name = "progmasoft"
                url = uri("https://maven.progmasoft.com/bigdata")
                credentials(PasswordCredentials::class)
            }
        }
    }

    extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        java {
            googleJavaFormat("2.0.0")
            trimTrailingWhitespace()
            endWithNewline()
        }
    }
}

spotless {
    kotlinGradle {
        target("*.gradle.kts", "*/*.gradle.kts")
        ktlint()
    }
}
