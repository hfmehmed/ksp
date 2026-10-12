package com.google.devtools.ksp.processing.impl

import com.google.devtools.ksp.common.AnyChanges
import com.google.devtools.ksp.common.NoSourceFile
import com.google.devtools.ksp.common.NoSourceWildcard
import com.google.devtools.ksp.common.impl.CodeGeneratorImpl
import com.google.devtools.ksp.processing.Dependencies
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CodeGeneratorImplTest {

    lateinit var codeGenerator: CodeGeneratorImpl
    lateinit var baseDir: File

    @Before
    fun setup() {
        baseDir = Files.createTempDirectory("project").toFile()
        val classesDir = File(baseDir, "classes")
        classesDir.mkdir()
        val javaDir = File(baseDir, "java")
        javaDir.mkdir()
        val kotlinDir = File(baseDir, "kotlin")
        kotlinDir.mkdir()
        val resourcesDir = File(baseDir, "resources")
        resourcesDir.mkdir()
        codeGenerator = CodeGeneratorImpl(
            classesDir,
            { javaDir },
            kotlinDir,
            resourcesDir,
            baseDir,
            AnyChanges(baseDir),
            emptyList(),
            true
        )
    }

    @Test
    fun testCreatingAFile() {
        codeGenerator.createNewFile(Dependencies.ALL_FILES, "a.b.c", "Test", "java")
        codeGenerator.createNewFile(Dependencies.ALL_FILES, "a.b.c", "Test", "kt")
        codeGenerator.createNewFile(Dependencies.ALL_FILES, "a.b.c", "Test", "class")
        codeGenerator.createNewFile(Dependencies.ALL_FILES, "a.b.c", "Test", "")

        val files = codeGenerator.generatedFile.toList()
        Assert.assertEquals(File(baseDir, "java/a/b/c/Test.java"), files[0])
        Assert.assertEquals(File(baseDir, "kotlin/a/b/c/Test.kt"), files[1])
        Assert.assertEquals(File(baseDir, "classes/a/b/c/Test.class"), files[2])
        Assert.assertEquals(File(baseDir, "resources/a/b/c/Test"), files[3])

        try {
            codeGenerator.outputs
        } catch (e: Exception) {
            Assert.fail("Failed to get outputs: ${e.message}")
        }
    }

    @Test
    fun testCreatingAFileWithSlash() {
        codeGenerator.createNewFile(Dependencies.ALL_FILES, "a/b/c", "Test", "java")
        codeGenerator.createNewFile(Dependencies.ALL_FILES, "a/b/c", "Test", "kt")
        codeGenerator.createNewFile(Dependencies.ALL_FILES, "a/b/c", "Test", "class")
        codeGenerator.createNewFile(Dependencies.ALL_FILES, "a/b/c", "Test", "")

        val files = codeGenerator.generatedFile.toList()
        Assert.assertEquals(File(baseDir, "java/a/b/c/Test.java"), files[0])
        Assert.assertEquals(File(baseDir, "kotlin/a/b/c/Test.kt"), files[1])
        Assert.assertEquals(File(baseDir, "classes/a/b/c/Test.class"), files[2])
        Assert.assertEquals(File(baseDir, "resources/a/b/c/Test"), files[3])

        try {
            codeGenerator.outputs
        } catch (e: Exception) {
            Assert.fail("Failed to get outputs: ${e.message}")
        }
    }

    @Test
    fun testCreatingAFileWithPath() {
        codeGenerator.createNewFileByPath(Dependencies.ALL_FILES, "a/b/c/Test", "java")
        codeGenerator.createNewFileByPath(Dependencies.ALL_FILES, "a/b/c/Test")
        codeGenerator.createNewFileByPath(Dependencies.ALL_FILES, "a/b/c/Test", "class")
        codeGenerator.createNewFileByPath(Dependencies.ALL_FILES, "a/b/c/Test", "")

        val files = codeGenerator.generatedFile.toList()
        Assert.assertEquals(File(baseDir, "java/a/b/c/Test.java"), files[0])
        Assert.assertEquals(File(baseDir, "kotlin/a/b/c/Test.kt"), files[1])
        Assert.assertEquals(File(baseDir, "classes/a/b/c/Test.class"), files[2])
        Assert.assertEquals(File(baseDir, "resources/a/b/c/Test"), files[3])

        try {
            codeGenerator.outputs
        } catch (e: Exception) {
            Assert.fail("Failed to get outputs: ${e.message}")
        }
    }

    @Test
    fun testCreatingAFileWithPathAndDots() {
        codeGenerator.createNewFileByPath(Dependencies.ALL_FILES, "a/b/c/dir.with.dot/Test", "java")
        codeGenerator.createNewFileByPath(Dependencies.ALL_FILES, "a/b/c/dir.with.dot/Test")
        codeGenerator.createNewFileByPath(Dependencies.ALL_FILES, "a/b/c/dir.with.dot/Test", "class")
        codeGenerator.createNewFileByPath(Dependencies.ALL_FILES, "a/b/c/dir.with.dot/Test", "")

        val files = codeGenerator.generatedFile.toList()
        Assert.assertEquals(File(baseDir, "java/a/b/c/dir.with.dot/Test.java"), files[0])
        Assert.assertEquals(File(baseDir, "kotlin/a/b/c/dir.with.dot/Test.kt"), files[1])
        Assert.assertEquals(File(baseDir, "classes/a/b/c/dir.with.dot/Test.class"), files[2])
        Assert.assertEquals(File(baseDir, "resources/a/b/c/dir.with.dot/Test"), files[3])

        try {
            codeGenerator.outputs
        } catch (e: Exception) {
            Assert.fail("Failed to get outputs: ${e.message}")
        }
    }

    @Test
    fun testCreatingAFileByPathWithInvalidPath() {
        try {
            codeGenerator.createNewFileByPath(Dependencies.ALL_FILES, "../../b/c/Test", "java")
            Assert.fail()
        } catch (e: java.lang.IllegalStateException) {
            Assert.assertEquals(e.message, "requested path is outside the bounds of the required directory")
        }
    }

    @Test
    fun testCreatingFileWithNoSource() {
        val noSourceKey = File(NoSourceWildcard(baseDir).filePath).relativeTo(baseDir)

        // Calling associate with empty sources on an uncreated file should be a no-op
        codeGenerator.associate(emptyList(), "a.b.c", "Uncreated", "kt")
        Assert.assertFalse(codeGenerator.sourceToOutputs.containsKey(noSourceKey))

        codeGenerator.createNewFile(Dependencies(false), "a.b.c", "NoSrc", "kt")
        val expectedOutput = File("kotlin/a/b/c/NoSrc.kt")

        Assert.assertEquals(setOf(expectedOutput), codeGenerator.sourceToOutputs[noSourceKey])

        // Calling associate with empty sources on an unassociated created file should keep it in NoSourceWildcard
        codeGenerator.associate(emptyList(), "a.b.c", "NoSrc", "kt")
        Assert.assertEquals(setOf(expectedOutput), codeGenerator.sourceToOutputs[noSourceKey])

        // Associating a source afterwards should remove the output from NoSourceWildcard
        val srcFile = NoSourceFile(baseDir, "a.b.c.SomeClass")
        val srcKey = File(srcFile.filePath).relativeTo(baseDir)
        codeGenerator.associate(listOf(srcFile), "a.b.c", "NoSrc", "kt")

        Assert.assertFalse(codeGenerator.sourceToOutputs.containsKey(noSourceKey))
        Assert.assertEquals(setOf(expectedOutput), codeGenerator.sourceToOutputs[srcKey])

        // Subsequent empty associate should not re-add to NoSourceWildcard when already associated
        codeGenerator.associate(emptyList(), "a.b.c", "NoSrc", "kt")
        Assert.assertFalse(codeGenerator.sourceToOutputs.containsKey(noSourceKey))
        Assert.assertEquals(setOf(expectedOutput), codeGenerator.sourceToOutputs[srcKey])

        // Associating a source before createNewFile(Dependencies(false), ...) should not add to NoSourceWildcard
        val preAssociatedOutput = File("kotlin/a/b/c/PreAssociated.kt")
        codeGenerator.associate(listOf(srcFile), "a.b.c", "PreAssociated", "kt")
        codeGenerator.createNewFile(Dependencies(false), "a.b.c", "PreAssociated", "kt")
        Assert.assertFalse(codeGenerator.sourceToOutputs.containsKey(noSourceKey))
        Assert.assertEquals(setOf(expectedOutput, preAssociatedOutput), codeGenerator.sourceToOutputs[srcKey])

        // Non-incremental CodeGeneratorImpl should keep sourceToOutputs empty even for no-source files
        val nonIncrementalCodeGen = CodeGeneratorImpl(
            File(baseDir, "classes"),
            { File(baseDir, "java") },
            File(baseDir, "kotlin"),
            File(baseDir, "resources"),
            baseDir,
            AnyChanges(baseDir),
            emptyList(),
            false
        )
        nonIncrementalCodeGen.createNewFile(Dependencies(false), "a.b.c", "NonIncNoSrc", "kt")
        Assert.assertTrue(nonIncrementalCodeGen.sourceToOutputs.isEmpty())
    }
}
