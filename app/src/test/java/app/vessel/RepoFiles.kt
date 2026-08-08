package app.vessel

import java.io.File

/**
 * The repository, from inside a unit test.
 *
 * A handful of the invariants worth asserting are not about a Kotlin object at
 * all — that the licence text shipped in `res/raw` is the same bytes as the one
 * in the repo root, that the R8 keep rules for the vendored LGPL packages are
 * still there, that the file in `res/font` really is Inter. Those are facts
 * about *files*, and the only honest way to test them is to read the files.
 *
 * Gradle runs unit tests with the module directory as the working directory, but
 * that is a default rather than a promise, so this walks up from wherever it
 * starts and looks for two markers that only the root has. Failing loudly beats
 * silently skipping: a licensing test that quietly passes because it could not
 * find the repository is worse than no test.
 */
object RepoFiles {

    val root: File by lazy {
        var candidate: File? = File("").absoluteFile
        while (candidate != null) {
            if (File(candidate, "settings.gradle.kts").isFile &&
                File(candidate, "LICENSE-LGPL-2.1").isFile
            ) {
                return@lazy candidate
            }
            candidate = candidate.parentFile
        }
        error(
            "could not find the repository root above ${File("").absolutePath} — " +
                "looked for a directory holding both settings.gradle.kts and LICENSE-LGPL-2.1",
        )
    }

    /** A repo-relative path, asserted to exist so a rename fails here and not later. */
    fun file(path: String): File {
        val file = File(root, path)
        check(file.isFile) { "expected a file at $path, relative to ${root.absolutePath}" }
        return file
    }

    fun directory(path: String): File {
        val file = File(root, path)
        check(file.isDirectory) { "expected a directory at $path, relative to ${root.absolutePath}" }
        return file
    }
}
