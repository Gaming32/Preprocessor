package com.replaymod.gradle.preprocess

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileTree
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.*
import org.gradle.api.tasks.compile.AbstractCompile
import org.gradle.kotlin.dsl.mapProperty
import org.gradle.kotlin.dsl.property
import java.io.File
import java.io.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Pattern

data class Keywords(
        val disableRemap: String,
        val enableRemap: String,
        val `if`: String,
        val ifdef: String,
        val elseif: String,
        val `else`: String,
        val endif: String,
        val eval: String
) : Serializable

@CacheableTask
open class PreprocessTask : DefaultTask() {
    companion object {
        @JvmStatic
        val DEFAULT_KEYWORDS = Keywords(
                disableRemap = "//#disable-remap",
                enableRemap = "//#enable-remap",
                `if` = "//#if",
                ifdef = "//#ifdef",
                elseif = "//#elseif",
                `else` = "//#else",
                endif = "//#endif",
                eval = "//$$"
        )
        @JvmStatic
        val CFG_KEYWORDS = Keywords(
                disableRemap = "##disable-remap",
                enableRemap = "##enable-remap",
                `if` = "##if",
                ifdef = "##ifdef",
                elseif = "##elseif",
                `else` = "##else",
                endif = "##endif",
                eval = "#$$"
        )
    }

    data class InOut(
        val source: FileCollection,
        val generated: File,
        val overwrites: File?,
    )

    @Internal
    var entries: MutableList<InOut> = mutableListOf()

    @InputFiles
    @SkipWhenEmpty
    @PathSensitive(PathSensitivity.RELATIVE)
    fun getSourceFileTrees(): List<ConfigurableFileTree> {
        return entries.flatMap { it.source }.map { project.fileTree(it) }
    }

    @InputFiles
    @Optional
    @PathSensitive(PathSensitivity.RELATIVE)
    fun getOverwritesFileTrees(): List<ConfigurableFileTree> {
        return entries.mapNotNull { it.overwrites?.let(project::fileTree) }
    }

    @OutputDirectories
    fun getGeneratedDirectories(): List<File> {
        return entries.map { it.generated }
    }

    private fun updateFirstInOut(update: InOut.() -> InOut) {
        val first = entries.removeFirstOrNull()
            ?: InOut(project.files(), File("invalid"), null)
        first.update()
        entries.add(0, first)
    }

    @Deprecated("Instead add an entry to `entries`.")
    @get:Internal
    var generated: File?
        get() = entries.firstOrNull()?.generated
        set(value) = updateFirstInOut { copy(generated = value ?: File("invalid")) }

    @Deprecated("Instead add an entry to `entries`.")
    @get:Internal
    var source: FileCollection?
        get() = entries.firstOrNull()?.source
        set(value) = updateFirstInOut { copy(source = value ?: project.files()) }

    @InputFile
    @Optional
    @PathSensitive(PathSensitivity.NONE)
    var mapping: File? = null

    @Input
    var reverseMapping: Boolean = false

    @InputDirectory
    @Optional
    @PathSensitive(PathSensitivity.RELATIVE)
    val jdkHome = project.objects.directoryProperty()

    @InputDirectory
    @Optional
    @PathSensitive(PathSensitivity.RELATIVE)
    val remappedjdkHome = project.objects.directoryProperty()

    @InputFiles
    @Optional
    @CompileClasspath
    var classpath: FileCollection? = null

    @InputFiles
    @Optional
    @CompileClasspath
    var remappedClasspath: FileCollection? = null

    @Input
    val vars = project.objects.mapProperty<String, Int>()

    @Input
    val keywords = project.objects.mapProperty<String, Keywords>()

    @Input
    @Optional
    val patternAnnotation = project.objects.property<String>()

    @Input
    @Optional
    val manageImports = project.objects.property<Boolean>()

    @Deprecated("Instead add an entry to `entries`.",
        replaceWith = ReplaceWith(expression = "entry(project.file(file), generated, overwrites)"))
    fun source(file: Any) {
        @Suppress("DEPRECATION")
        source = project.files(file)
    }

    @Deprecated("Instead add an entry to `entries`.",
        replaceWith = ReplaceWith(expression = "entry(source, project.file(file), overwrites)"))
    fun generated(file: Any) {
        @Suppress("DEPRECATION")
        generated = project.file(file)
    }

    fun entry(source: FileCollection, generated: File, overwrites: File) {
        entries.add(InOut(source, generated, overwrites))
    }

    @Deprecated("Unnecessarily depends on the task output. Instead set `classpath` directly.",
        replaceWith = ReplaceWith(expression = "classpath = task.classpath"))
    fun compileTask(task: AbstractCompile) {
        dependsOn(task)
        classpath = (classpath ?: project.files()) + task.classpath + project.files(task.destinationDir)
    }

    @TaskAction
    fun preprocess() {
        data class Entry(val relPath: String, val inBase: Path, val outBase: Path, val overwritesBase: Path?)
        val sourceFiles: List<Entry> = entries.flatMap { inOut ->
            val outBasePath = inOut.generated.toPath()
            val overwritesBasePath = inOut.overwrites?.toPath()
            inOut.source.flatMap { inBase ->
                val inBasePath = inBase.toPath()
                project.fileTree(inBase).map { file ->
                    val relPath = inBasePath.relativize(file.toPath())
                    Entry(relPath.toString(), inBasePath, outBasePath, overwritesBasePath)
                }
            }
        }

        project.delete(entries.map { it.generated })

        val commentPreprocessor = CommentPreprocessor(vars.get())
        sourceFiles.forEach { (relPath, inBase, outBase, overwritesPath) ->
            val file = inBase.resolve(relPath).toFile()
            val outFile = outBase.resolve(relPath).toFile()
            if (overwritesPath != null && Files.exists(overwritesPath.resolve(relPath))) {
                return@forEach
            }
            val kws = keywords.get().entries.find { (ext, _) -> file.name.endsWith(ext) }
            if (kws != null) {
                val javaTransform = { lines: List<String> ->
                    lines.map { Pair(it, emptyList<String>()) }
                }
                commentPreprocessor.convertFile(kws.value, file, outFile, javaTransform)
            } else {
                project.copy {
                    from(file)
                    into(outFile.parentFile)
                }
            }
        }

        if (commentPreprocessor.fail) {
            throw GradleException("Failed to remap sources. See errors above for details.")
        }
    }
}

class CommentPreprocessor(
    private val vars: Map<String, Int>
) {
    companion object {
        private val EXPR_PATTERN = Pattern.compile("(.+)(==|!=|<=|>=|<|>)(.+)")
        private val OR_PATTERN = Pattern.quote("||").toPattern()
        private val AND_PATTERN = Pattern.quote("&&").toPattern()
    }

    var fail = false

    private fun String.evalVarOrNull() = cleanUpIfVersion().toIntOrNull() ?: vars[this]
    private fun String.evalVar() = cleanUpIfVersion().evalVarOrNull() ?: throw NoSuchElementException("\'$this\' is not in $vars")

    private fun String.cleanUpIfVersion(): String {
        // Verify that this could be a version
        if (!matches("[\\d._+-]+".toRegex()))
            return this

        // Version handling
        val matcher = Pattern.compile("(\\d+)\\.(\\d+)(?:\\.(\\d+))?").matcher(this)
        if (matcher.matches()) {
            val major = matcher.group(1) ?: "0"
            val minor = matcher.group(2)?.padStart(2, '0') ?: "00"
            val patch = matcher.group(3)?.padStart(2, '0') ?: "00"
            return "$major$minor$patch"
        }

        // Underscore handling
        return if (startsWith("_")) this else replace("_", "", false)
    }

    internal fun String.evalExpr(): Boolean {
        split(OR_PATTERN).let { parts ->
            if (parts.size > 1) {
                return parts.any { it.trim().evalExpr() }
            }
        }

        split(AND_PATTERN).let { parts ->
            if (parts.size > 1) {
                return parts.all { it.trim().evalExpr() }
            }
        }

        val result = evalVarOrNull()
        if (result != null) {
            return result != 0
        }

        val matcher = EXPR_PATTERN.matcher(this)
        if (matcher.matches()) {
            val leftExpr = matcher.group(1).trim()
            val rightExpr = matcher.group(3).trim()
            val lhs = leftExpr.evalVar()
            val rhs = rightExpr.evalVar()
            return when (matcher.group(2)) {
                "==" -> lhs == rhs
                "!=" -> lhs != rhs
                ">=" -> lhs >= rhs
                "<=" -> lhs <= rhs
                ">" -> lhs > rhs
                "<" -> lhs < rhs
                else -> throw InvalidExpressionException(this)
            }
        } else {
            throw InvalidExpressionException(this)
        }
    }

    private val String.indentation: Int
        get() = takeWhile { it == ' ' }.length

    fun convertSource(kws: Keywords, lines: List<String>, remapped: List<Pair<String, List<String>>>, fileName: String): List<String> {
        val stack = mutableListOf<IfStackEntry>()
        val indentStack = mutableListOf<Int>()
        var active = true
        var remapActive = true
        var n = 0

        fun evalCondition(condition: String): Boolean {
            if (!condition.startsWith(" "))
                throw ParserException("Expected space before condition in line $n of $fileName")
            try {
                return condition.trim().evalExpr()
            } catch (e: InvalidExpressionException) {
                throw ParserException("Invalid expression \"${e.message}\" in line $n of $fileName")
            }
        }

        return lines.zip(remapped).map { (originalLine, lineMapped) ->
            val (line, errors) = lineMapped
            var ignoreErrors = false
            n++
            val trimmed = line.trim()
            val mapped = if (trimmed.startsWith(kws.`if`)) {
                val result = evalCondition(trimmed.substring(kws.`if`.length))
                stack.add(IfStackEntry(result, n, elseFound = false, trueFound = result))
                indentStack.add(line.indentation)
                active = active && result
                line
            } else if (trimmed.startsWith(kws.elseif)) {
                if (stack.isEmpty()) {
                    throw ParserException("Unexpected elseif in line $n of $fileName")
                }
                if (stack.last().elseFound) {
                    throw ParserException("Unexpected elseif after else in line $n of $fileName")
                }

                indentStack.removeLast()
                indentStack.add(line.indentation)

                active = if (stack.last().trueFound) {
                    val last = stack.removeLast()
                    stack.add(last.copy(currentValue = false))
                    false
                } else {
                    val result = evalCondition(trimmed.substring(kws.elseif.length))
                    stack.removeLast()
                    stack.add(IfStackEntry(result, n, elseFound = false, trueFound = result))
                    stack.all { it.currentValue }
                }
                line
            } else if (trimmed.startsWith(kws.`else`)) {
                if (stack.isEmpty()) {
                    throw ParserException("Unexpected else in line $n of $fileName")
                }
                val entry = stack.removeLast()
                stack.add(IfStackEntry(!entry.trueFound, n, elseFound = true, trueFound = entry.trueFound))
                indentStack.removeLast()
                indentStack.add(line.indentation)
                active = stack.all { it.currentValue }
                line
            } else if (trimmed.startsWith(kws.ifdef)) {
                val result = vars.containsKey(trimmed.substring(kws.ifdef.length))
                stack.add(IfStackEntry(result, n, elseFound = false, trueFound = result))
                indentStack.add(line.indentation)
                active = active && result
                line
            } else if (trimmed.startsWith(kws.endif)) {
                if (stack.isEmpty()) {
                    throw ParserException("Unexpected endif in line $n of $fileName")
                }
                stack.removeLast()
                indentStack.removeLast()
                active = stack.all { it.currentValue }
                line
            } else if (trimmed.startsWith(kws.disableRemap)) {
                if (!remapActive) {
                    throw ParserException("Remapping already disabled in line $n of $fileName")
                }
                remapActive = false
                line
            } else if (trimmed.startsWith(kws.enableRemap)) {
                if (remapActive) {
                    throw ParserException("Remapping not disabled in line $n of $fileName")
                }
                remapActive = true
                line
            } else {
                if (active) {
                    if (trimmed.startsWith(kws.eval)) {
                        line.replaceFirst((Pattern.quote(kws.eval) + " ?").toRegex(), "").let {
                            if (it.trim().isEmpty()) {
                                ""
                            } else {
                                it
                            }
                        }
                    } else if (remapActive) {
                        line
                    } else {
                        ignoreErrors = true
                        originalLine
                    }
                } else {
                    val currIndent = indentStack.last()
                    if (trimmed.isEmpty()) {
                        " ".repeat(currIndent) + kws.eval
                    } else if (!trimmed.startsWith(kws.eval) && currIndent <= line.indentation) {
                        // Line has been disabled, so we want to use its non-remapped content instead.
                        // For one, the remapped content would be useless anyway since it's commented out
                        // and, more importantly, if we do not preserve it, we might permanently loose it as the
                        // remap process is only guaranteed to work on code which compiles and since we're
                        // just about to comment it out, it probably doesn't compile.
                        ignoreErrors = true
                        " ".repeat(currIndent) + kws.eval + " " + originalLine.substring(currIndent)
                    } else {
                        line
                    }
                }
            }
            if (errors.isNotEmpty() && !ignoreErrors) {
                fail = true
                for (message in errors) {
                    System.err.println("$fileName:$n: $message")
                }
            }
            mapped
        }.also {
            if (stack.isNotEmpty()) {
                throw ParserException("Missing endif on line ${stack.lastOrNull()?.lineno} of $fileName")
            }
        }
    }

    fun convertFile(kws: Keywords, inFile: File, outFile: File, remap: ((List<String>) -> List<Pair<String, List<String>>>)? = null) {
        val string = inFile.readText()
        var lines = string.lines()
        val remapped = remap?.invoke(lines) ?: lines.map { Pair(it, emptyList()) }
        try {
            lines = convertSource(kws, lines, remapped, inFile.path)
        } catch (e: Throwable) {
            if (e is ParserException) {
                throw e
            }
            throw RuntimeException("Failed to convert file $inFile", e)
        }
        outFile.parentFile.mkdirs()
        outFile.writeText(lines.joinToString("\n"))
    }

    data class IfStackEntry(
        var currentValue: Boolean,
        var lineno: Int,
        var elseFound: Boolean = false,
        var trueFound: Boolean = false
    )

    class InvalidExpressionException(expr: String) : RuntimeException(expr)

    class ParserException(str: String) : RuntimeException(str)
}
