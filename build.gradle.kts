/* Copyright (C) 2019 Jonas Herzig <me@johni0702.de>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

plugins {
    kotlin("jvm") version("1.9.23")
    `kotlin-dsl`
    `maven-publish`
    groovy
}

group = "io.github.gaming32"
version = "0.4.3"

val kotestVersion: String by project.extra

java {
    withSourcesJar()
    toolchain.languageVersion.set(JavaLanguageVersion.of(8))
}

repositories {
    mavenLocal()
    mavenCentral()
    maven(url = "https://maven.fabricmc.net/")
    maven(url = "https://maven.wagyourtail.xyz/releases/")
    maven(url = "https://maven.wagyourtail.xyz/snapshots/")
}

dependencies {
    implementation(gradleApi())
    implementation(localGroovy())
    implementation("xyz.wagyourtail.unimined:source-remap:1.0.4-SNAPSHOT")
    implementation("net.fabricmc:tiny-mappings-parser:0.2.1.13")
    testImplementation("io.kotest:kotest-runner-junit5-jvm:$kotestVersion")
    testImplementation("io.kotest:kotest-assertions-core-jvm:$kotestVersion")
}

gradlePlugin {
    plugins {
        register("preprocess") {
            id = "io.github.gaming32.gradle.preprocess"
            implementationClass = "com.replaymod.gradle.preprocess.PreprocessPlugin"
        }

        register("preprocess-root") {
            id = "io.github.gaming32.gradle.preprocess-root"
            implementationClass = "com.replaymod.gradle.preprocess.RootPreprocessPlugin"
        }
    }
}

publishing {
    repositories {
        fun maven(name: String, releases: String, snapshots: String) {
            maven {
                this.name = name
                url = uri(if (version.toString().endsWith("-SNAPSHOT")) snapshots else releases)
                credentials(PasswordCredentials::class)
                authentication {
                    create<BasicAuthentication>("basic")
                }
            }
        }

        maven(
            "gaming32",
            "https://maven.jemnetworks.com/releases",
            "https://maven.jemnetworks.com/snapshots"
        )
    }

    publications {
        create<MavenPublication>("maven") {
            artifactId = project.name
            groupId = project.group.toString()
            version = project.version.toString()

            from(components["java"])

            pom {
                name = project.name
                licenses {
                    license {
                        name = "GPL-3.0-or-later"
                        url = "https://github.com/Gaming32/preprocessor/blob/master/LICENSE.md"
                    }
                }
                developers {
                    developer {
                        id = "Gaming32"
                    }
                }
            }
        }
    }
}

tasks {
    withType<Test> {
        useJUnitPlatform()
    }

    jar {
        manifest {
            attributes["Implementation-Version"] = version
        }
    }
}
