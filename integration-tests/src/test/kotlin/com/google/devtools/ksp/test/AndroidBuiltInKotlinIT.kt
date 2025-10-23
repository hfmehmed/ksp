package com.google.devtools.ksp.test

import org.gradle.testkit.runner.GradleRunner
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import java.io.File

class AndroidBuiltInKotlinIT {
    @Rule
    @JvmField
    val project: TemporaryTestProject = TemporaryTestProject("playground-android-builtinkotlin", "playground")

    @Test
    fun testPlaygroundAndroidWithBuiltInKotlinAGP813() {
        val gradleRunner = GradleRunner.create().withProjectDir(project.root)

        File(project.root, "gradle.properties").appendText("\nagpVersion=8.13.0")

        gradleRunner.withArguments(
            "clean", "build", "minifyReleaseWithR8", "--configuration-cache", "--info", "--stacktrace"
        ).buildAndFail().let { result ->
            Assert.assertTrue(
                result.output.contains(
                    "KSP is not compatible with Android Gradle Plugin's built-in Kotlin. Please disable " +
                        "by adding android.builtInKotlin=false and android.newDsl=false to gradle.properties " +
                        "and apply kotlin(\"android\") plugin"
                )
            )
        }
    }

    @Test
    fun testPlaygroundAndroidWithBuiltInKotlinAGP90() {
        val gradleRunner = GradleRunner.create().withProjectDir(project.root)

        File(project.root, "gradle.properties").appendText("\nagpVersion=9.0.0-alpha05")

        gradleRunner.withArguments(
            "clean", "build", "minifyReleaseWithR8", "--configuration-cache", "--info", "--stacktrace"
        ).buildAndFail().let { result ->
            Assert.assertTrue(
                result.output.contains(
                    "KSP is not compatible with Android Gradle Plugin's built-in Kotlin. Please disable " +
                        "by adding android.builtInKotlin=false and android.newDsl=false to gradle.properties " +
                        "and apply kotlin(\"android\") plugin"
                )
            )
        }
    }
}
