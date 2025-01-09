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
    kotlin("jvm") version("2.0.21")
    `kotlin-dsl`
    `maven-publish`
    groovy
}

group = "io.github.gaming32"
version = "0.4.5"

val kotestVersion: String by project.extra

java {
    withSourcesJar()
    toolchain.languageVersion.set(JavaLanguageVersion.of(8))
}

repositories {
    mavenLocal()
    mavenCentral()
    maven(url = "https://jitpack.io/")
    maven(url = "https://maven.fabricmc.net/")
    maven(url = "https://maven.deftu.xyz/releases/")
}

dependencies {
    implementation(gradleApi())
    implementation(localGroovy())
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
            }
        }

        maven(
            "gaming32",
            "https://maven.jemnetworks.com/releases",
            "https://maven.jemnetworks.com/snapshots"
        )
    }

    publications {
        create<MavenPublication>("pluginMaven") {
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
