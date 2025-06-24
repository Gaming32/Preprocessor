package com.replaymod.gradle.preprocess

open class RootPreprocessExtension : ProjectGraphNodeDSL {
    var rootNode: ProjectGraphNode? = null
        get() = field ?: linkNodes()?.also { field = it }

    private val nodes = mutableSetOf<Node>()

    fun createNode(project: String, mcVersion: Int): Node {
        return Node(project, mcVersion).also { nodes.add(it) }
    }

    private fun linkNodes(): ProjectGraphNode? {
        val first = nodes.firstOrNull() ?: return null
        val visited = mutableSetOf<Node>()
        fun Node.breadthFirstSearch(): ProjectGraphNode {
            val graphNode = ProjectGraphNode(project, mcVersion)
            links.forEach { otherNode ->
                if (visited.add(otherNode)) {
                    graphNode.links.add(otherNode.breadthFirstSearch())
                }
            }
            return graphNode
        }
        return first.breadthFirstSearch()
    }

    override fun addNode(project: String, mcVersion: Int): ProjectGraphNode {
        check(rootNode == null) { "Only one root node may be set." }
        return ProjectGraphNode(project, mcVersion).also { rootNode = it }
    }
}

class Node(
    val project: String,
    val mcVersion: Int,
) {
    internal val links = mutableSetOf<Node>()

    fun link(other: Node) {
        this.links.add(other)
        other.links.add(this)
    }
}

interface ProjectGraphNodeDSL {
    operator fun String.invoke(mcVersion: Int, configure: ProjectGraphNodeDSL.() -> Unit = {}) {
        addNode(this, mcVersion).configure()
    }

    fun addNode(project: String, mcVersion: Int): ProjectGraphNodeDSL
}

open class ProjectGraphNode(
    val project: String,
    val mcVersion: Int,
    val links: MutableList<ProjectGraphNode> = mutableListOf()
) : ProjectGraphNodeDSL {
    override fun addNode(project: String, mcVersion: Int): ProjectGraphNodeDSL =
            ProjectGraphNode(project, mcVersion).also { links.add(it) }

    fun findNode(project: String): ProjectGraphNode? = if (project == this.project) {
        this
    } else {
        links.map { it.findNode(project) }.find { it != null }
    }

    fun findParent(node: ProjectGraphNode): ProjectGraphNode? = if (node == this) {
        null
    } else {
        links.map { child ->
            if (child == node) {
                this
            } else {
                child.findParent(node)
            }
        }.find { it != null }
    }
}
