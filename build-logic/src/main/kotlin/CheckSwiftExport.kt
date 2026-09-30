import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SkipWhenEmpty
import org.gradle.api.tasks.TaskAction

/**
 * Fails on source constructs that consumers' Swift export can't handle.
 *
 * Nothing in this build notices them otherwise: the module compiles, its tests pass and its ABI
 * dump updates cleanly, and only a consumer's iOS build breaks. A plain text scan is enough because
 * each construct is a single token or import. It scans every main source file, internal ones too,
 * so a dependency on these libraries can't creep in and reach the public API later.
 */
@CacheableTask
abstract class CheckSwiftExport : DefaultTask() {
  @get:InputFiles
  @get:SkipWhenEmpty
  @get:IgnoreEmptyDirectories
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val sources: ConfigurableFileCollection

  /** Written only on success, so the task is up to date until a source changes. */
  @get:OutputFile abstract val marker: RegularFileProperty

  @TaskAction
  fun check() {
    val violations =
      sources.files
        .sortedBy { it.path }
        .flatMap { file ->
          file.readLines().mapIndexedNotNull { index, line ->
            val code = line.trim()
            if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) {
              return@mapIndexedNotNull null
            }
            forbidden
              .firstOrNull { (pattern, _) -> pattern.containsMatchIn(code) }
              ?.let { (_, why) -> "${file.path}:${index + 1}: $why\n    $code" }
          }
        }

    if (violations.isNotEmpty()) {
      throw GradleException(
        "Swift export can't handle these, and nothing else in this build would catch them " +
          "(see .claude/rules/swift-export.md):\n" +
          violations.joinToString("\n")
      )
    }
    marker.get().asFile.writeText("ok\n")
  }

  private companion object {
    val forbidden =
      listOf(
        Regex("""\bsealed\s+interface\b""") to "use `sealed class`, not `sealed interface`",
        Regex("""\bkotlinx\.collections\.immutable\.""") to
          "use `List`; `@Immutable` on the class already makes it stable",
        // runtime-annotation holds only these annotations and no Compose types, so they're safe.
        Regex("""\bandroidx\.compose\.(?!runtime\.(Immutable|Stable)\b)""") to
          "no Compose types in an exported module",
      )
  }
}
