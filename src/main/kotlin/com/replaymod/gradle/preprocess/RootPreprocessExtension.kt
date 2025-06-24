package com.replaymod.gradle.preprocess

open class RootPreprocessExtension {
    private val versions = mutableMapOf<String, Int>()

    fun addProject(project: String, mcVersion: Int) {
        versions[project] = mcVersion
    }

    fun getVersion(project: String): Int? = versions[project]
}
