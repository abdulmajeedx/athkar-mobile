plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.ksp) apply false
    alias(libs.plugins.kotlin.kapt) apply false
    alias(libs.plugins.hilt) apply false
}

/**
 * Enforcement of the 3-layer rule: Domain/Core modules must NEVER import any Android/UI or
 * platform package. This fails the build at the first violation (per delivery brief).
 */
subprojects {
    afterEvaluate {
        val isDomainLike = project.path == ":domain" || project.path == ":core"
        if (!isDomainLike) return@afterEvaluate

        tasks.register("enforcePureDomainLayers") {
            val projectPath = project.path
            val rootDir = rootProject.projectDir
            val srcDir = project.layout.projectDirectory.dir("src/main/kotlin").asFile
            doLast {
                if (!srcDir.exists()) return@doLast
                val forbidden = listOf(
                    "android.", "androidx.", "com.google.android.",
                    "java.awt", "javax.swing", "android.app", "android.os",
                )
                val violations = mutableListOf<String>()
                srcDir.walkTopDown()
                    .filter { it.extension == "kt" }
                    .forEach { file ->
                        file.useLines { lines ->
                            lines.forEach { line ->
                                for (f in forbidden) {
                                    if (line.trim().startsWith("import $f")) {
                                        violations.add("${file.relativeTo(rootDir)}: $line")
                                    }
                                }
                            }
                        }
                    }
                if (violations.isNotEmpty()) {
                    throw GradleException(
                        "Domain-layer purity violation detected in $projectPath:\n" +
                            violations.joinToString("\n") + "\n" +
                            "The domain/core layer must not import any Android/UI/platform package."
                    )
                }
            }
        }
        tasks.named("check") {
            dependsOn("enforcePureDomainLayers")
        }
    }
}
