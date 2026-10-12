/*
 * Copyright 2026 Google LLC
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("DEPRECATION")

package com.google.devtools.ksp.common

import com.google.devtools.ksp.IncrementalContextLoggingOptions
import com.google.devtools.ksp.common.impl.CodeGeneratorImpl
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSName
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeArgument
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.google.devtools.ksp.symbol.KSTypeReference
import com.google.devtools.ksp.symbol.KSVisitor
import com.google.devtools.ksp.symbol.Location
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Origin
import com.intellij.util.containers.MultiMap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class IncrementalContextBaseTest {

    private class FakeKSName(private val str: String) : KSName {
        override fun asString(): String = str
        override fun getShortName(): String = str.substringAfterLast('.')
        override fun getQualifier(): String = str.substringBeforeLast('.', "")
    }

    private class FakeKSClassDeclaration(
        private val pkg: String,
        private val name: String,
        override val containingFile: KSFile?
    ) : KSClassDeclaration {
        override val simpleName: KSName = FakeKSName(name)
        override val qualifiedName: KSName = FakeKSName(if (pkg.isEmpty()) name else "$pkg.$name")
        override val declarations: Sequence<KSDeclaration> = emptySequence()
        override val classKind: ClassKind get() = ClassKind.CLASS
        override val primaryConstructor: KSFunctionDeclaration? get() = null
        override val superTypes: Sequence<KSTypeReference> get() = emptySequence()
        override val isCompanionObject: Boolean get() = false
        override fun getSealedSubclasses(): Sequence<KSClassDeclaration> = emptySequence()
        override fun getAllFunctions(): Sequence<KSFunctionDeclaration> = emptySequence()
        override fun getAllProperties(): Sequence<KSPropertyDeclaration> = emptySequence()
        override fun asType(typeArguments: List<KSTypeArgument>): KSType = throw UnsupportedOperationException()
        override fun asStarProjectedType(): KSType = throw UnsupportedOperationException()
        override val typeParameters: List<KSTypeParameter> get() = emptyList()
        override val packageName: KSName = FakeKSName(pkg)
        override val parentDeclaration: KSDeclaration? get() = null
        override val docString: String? get() = null
        override val modifiers: Set<Modifier> get() = emptySet()
        override val origin: Origin get() = Origin.KOTLIN
        override val location: Location get() = throw UnsupportedOperationException()
        override val parent: KSNode? get() = containingFile
        override val annotations: Sequence<KSAnnotation> get() = emptySequence()
        override val isActual: Boolean get() = false
        override val isExpect: Boolean get() = false
        override fun findActuals(): Sequence<KSDeclaration> = emptySequence()
        override fun findExpects(): Sequence<KSDeclaration> = emptySequence()
        override fun <D, R> accept(visitor: KSVisitor<D, R>, data: D): R = visitor.visitClassDeclaration(this, data)
    }

    private class FakeKSFile(
        override val filePath: String,
        classNames: List<Pair<String, String>> = emptyList()
    ) : KSFile {
        override val fileName: String get() = File(filePath).name
        override val annotations: Sequence<KSAnnotation> get() = emptySequence()
        override val declarations: Sequence<KSDeclaration> =
            classNames.map { (pkg, name) -> FakeKSClassDeclaration(pkg, name, this) }.asSequence()
        override val packageName: KSName get() = throw UnsupportedOperationException()
        override val origin: Origin get() = Origin.KOTLIN
        override val location: Location get() = throw UnsupportedOperationException()
        override val parent: KSNode? get() = null
        override fun <D, R> accept(visitor: KSVisitor<D, R>, data: D): R = visitor.visitFile(this, data)
    }

    private class FakeLookupTracker : LookupTrackerWrapper {
        override val lookups: MultiMap<LookupSymbolWrapper, String> = MultiMap.createSet()
        override fun record(filePath: String, scopeFqName: String, name: String) {
            lookups.putValue(LookupSymbolWrapper(name, scopeFqName), filePath)
        }
    }

    private class FakeLookupStorage : LookupStorageWrapper {
        private val map: MultiMap<LookupSymbolWrapper, String> = MultiMap.createSet()
        override fun removeLookupsFrom(files: Sequence<File>) {
            val toRemove = files.map { it.path }.toSet()
            map.keySet().toList().forEach { key ->
                val remaining = map[key].filterNot { it in toRemove }
                map.remove(key)
                remaining.forEach { map.putValue(key, it) }
            }
        }
        override fun update(
            lookupTracker: LookupTrackerWrapper,
            filesToCompile: Iterable<File>,
            removedFiles: Iterable<File>
        ) {
            removeLookupsFrom((filesToCompile + removedFiles).asSequence())
            lookupTracker.lookups.entrySet().forEach { entry ->
                entry.value.forEach { map.putValue(entry.key, it) }
            }
        }
        override fun flush() {}
        override fun close() {}
        override fun get(lookupSymbol: LookupSymbolWrapper): Collection<String> = map[lookupSymbol]
    }

    private class TestIncrementalContext(
        anyChangesWildcard: File,
        baseDir: File,
        cachesDir: File,
        kspOutputDir: File,
        knownModified: List<File>,
        knownRemoved: List<File>,
        override val symbolLookupCache: LookupStorageWrapper,
        override val classLookupCache: LookupStorageWrapper,
    ) : IncrementalContextBase(
        anyChangesWildcard,
        IncrementalContextLoggingOptions(incrementalLoggingEnabled = true, dependencyGraphOriginName = null),
        baseDir,
        cachesDir,
        kspOutputDir,
        knownModified,
        knownRemoved,
        emptyList(),
    ) {
        override val isIncremental: Boolean = true
        public override val symbolLookupTracker: FakeLookupTracker = FakeLookupTracker()
        public override val classLookupTracker: FakeLookupTracker = FakeLookupTracker()
    }

    @Test
    fun testNoSourceWildcardMultiRoundAndRemoval(@TempDir tempDir: File) {
        val baseDir = tempDir.canonicalFile
        val cachesDir = File(baseDir, "caches").apply { mkdirs() }
        val outputDir = File(baseDir, "outputs").apply { mkdirs() }
        val classesDir = File(outputDir, "classes").apply { mkdirs() }
        val javaDir = File(outputDir, "java").apply { mkdirs() }
        val kotlinDir = File(outputDir, "kotlin").apply { mkdirs() }
        val resourcesDir = File(outputDir, "resources").apply { mkdirs() }

        val srcAFile = File(baseDir, "src/A.kt").apply {
            parentFile.mkdirs()
            writeText("package a\nclass A\n")
        }
        val ksFileA = FakeKSFile(srcAFile.path, listOf("a" to "A"))
        val anyChanges = AnyChanges(baseDir)
        val anyChangesRel = File(anyChanges.filePath).relativeTo(baseDir)
        val symbolStorage = FakeLookupStorage()
        val classStorage = FakeLookupStorage()

        // Build 1:
        // - A.kt generates AOut.kt and looks up symbol a.NoSrc1 from NoSrc1.kt
        // - NoSrc1.kt and NoSrc2.kt are generated with Dependencies(false) (no sources)
        // - In round 2, DerivedFromNoSrc2.kt is generated depending on NoSrc2.kt
        run {
            val codeGen = CodeGeneratorImpl(
                classesDir, { javaDir }, kotlinDir, resourcesDir,
                baseDir, anyChanges, listOf(ksFileA), true
            )
            val ic = TestIncrementalContext(
                anyChangesRel, baseDir, cachesDir, outputDir,
                listOf(srcAFile), emptyList(), symbolStorage, classStorage
            )
            val dirtyInitial = ic.calcDirtyFiles(listOf(ksFileA))
            assertEquals(listOf(ksFileA), dirtyInitial.toList())

            // Record that A.kt looked up a.NoSrc1
            ic.symbolLookupTracker.record(srcAFile.relativeTo(baseDir).path, "a", "NoSrc1")

            codeGen.createNewFile(Dependencies(false, ksFileA), "a", "AOut", "kt").close()
            codeGen.createNewFile(Dependencies(false), "a", "NoSrc1", "kt").close()
            codeGen.createNewFile(Dependencies(false), "a", "NoSrc2", "kt").close()

            val noSrc1File = File(kotlinDir, "a/NoSrc1.kt")
            val noSrc2File = File(kotlinDir, "a/NoSrc2.kt")
            val aOutFile = File(kotlinDir, "a/AOut.kt")
            val ksNoSrc1 = FakeKSFile(noSrc1File.path, listOf("a" to "NoSrc1"))
            val ksNoSrc2 = FakeKSFile(noSrc2File.path, listOf("a" to "NoSrc2"))
            val ksAOut = FakeKSFile(aOutFile.path, listOf("a" to "AOut"))
            ic.registerGeneratedFiles(listOf(ksNoSrc1, ksNoSrc2, ksAOut))

            // Round 2: DerivedFromNoSrc2.kt depends on NoSrc2.kt
            codeGen.createNewFile(Dependencies(false, ksNoSrc2), "a", "DerivedFromNoSrc2", "kt").close()
            val derivedFile = File(kotlinDir, "a/DerivedFromNoSrc2.kt")
            val ksDerived = FakeKSFile(derivedFile.path, listOf("a" to "DerivedFromNoSrc2"))
            ic.registerGeneratedFiles(listOf(ksDerived))

            val allDirtyKSFiles = listOf(ksFileA, ksAOut, ksNoSrc1, ksNoSrc2, ksDerived)
            ic.updateCachesAndOutputs(allDirtyKSFiles, codeGen.sourceToOutputs)
            ic.closeFiles()

            val logContent = File(cachesDir, "logs/kspSourceToOutputs.log").readText()
            assertTrue(logContent.contains("All reprocessed outputs"))
            assertTrue(logContent.contains("outputs/kotlin/a/NoSrc1.kt"))
            assertTrue(logContent.contains("outputs/kotlin/a/NoSrc2.kt"))
            assertTrue(logContent.contains("outputs/kotlin/a/DerivedFromNoSrc2.kt"))
        }

        // Build 2 (incremental):
        // Simulate Gradle cleaning outputDir before KSP runs.
        // Only NoSrc1.kt is generated with Dependencies(false); NoSrc2.kt is no longer generated.
        // Verify AOut.kt (from clean A.kt) IS restored, NoSrc1.kt is present,
        // and neither NoSrc2.kt nor DerivedFromNoSrc2.kt is restored from backup.
        run {
            outputDir.deleteRecursively()
            classesDir.mkdirs()
            javaDir.mkdirs()
            kotlinDir.mkdirs()
            resourcesDir.mkdirs()

            val codeGen = CodeGeneratorImpl(
                classesDir, { javaDir }, kotlinDir, resourcesDir,
                baseDir, anyChanges, listOf(ksFileA), true
            )
            val ic = TestIncrementalContext(
                anyChangesRel, baseDir, cachesDir, outputDir,
                emptyList(), emptyList(), symbolStorage, classStorage
            )
            val dirtyInitial = ic.calcDirtyFiles(listOf(ksFileA))
            assertTrue(dirtyInitial.isEmpty())

            codeGen.createNewFile(Dependencies(false), "a", "NoSrc1", "kt").close()
            val noSrc1File = File(kotlinDir, "a/NoSrc1.kt")
            val ksNoSrc1 = FakeKSFile(noSrc1File.path, listOf("a" to "NoSrc1"))
            ic.registerGeneratedFiles(listOf(ksNoSrc1))

            ic.updateCachesAndOutputs(listOf(ksNoSrc1), codeGen.sourceToOutputs)
            ic.closeFiles()

            assertTrue(File(kotlinDir, "a/AOut.kt").exists(), "Clean output AOut.kt should be restored from backup")
            assertTrue(File(kotlinDir, "a/NoSrc1.kt").exists(), "Generated output NoSrc1.kt should exist")
            assertFalse(File(kotlinDir, "a/NoSrc2.kt").exists(), "Removed output NoSrc2.kt should not be restored")
            assertFalse(
                File(kotlinDir, "a/DerivedFromNoSrc2.kt").exists(),
                "Transitive output DerivedFromNoSrc2.kt of removed NoSrc2.kt should not be restored"
            )
        }

        // Build 3 (incremental):
        // No no-source outputs are generated at all (NoSrc1.kt disappears).
        run {
            outputDir.deleteRecursively()
            classesDir.mkdirs()
            javaDir.mkdirs()
            kotlinDir.mkdirs()
            resourcesDir.mkdirs()

            val codeGen = CodeGeneratorImpl(
                classesDir, { javaDir }, kotlinDir, resourcesDir,
                baseDir, anyChanges, listOf(ksFileA), true
            )
            val ic = TestIncrementalContext(
                anyChangesRel, baseDir, cachesDir, outputDir,
                emptyList(), emptyList(), symbolStorage, classStorage
            )
            val dirtyInitial = ic.calcDirtyFiles(listOf(ksFileA))
            assertTrue(dirtyInitial.isEmpty())

            ic.updateCachesAndOutputs(emptyList(), codeGen.sourceToOutputs)
            ic.closeFiles()

            assertTrue(File(kotlinDir, "a/AOut.kt").exists(), "Clean output AOut.kt should be restored from backup")
            assertFalse(File(kotlinDir, "a/NoSrc1.kt").exists(), "Removed output NoSrc1.kt should not be restored")
        }

        // Build 4 (incremental):
        // Because NoSrc1.kt disappeared in Build 3 and A.kt looked up symbol a.NoSrc1,
        // calcDirtyFiles must mark A.kt dirty via removedOutputs propagation!
        run {
            val ic = TestIncrementalContext(
                anyChangesRel, baseDir, cachesDir, outputDir,
                emptyList(), emptyList(), symbolStorage, classStorage
            )
            val dirtyInitial = ic.calcDirtyFiles(listOf(ksFileA))
            assertEquals(
                listOf(ksFileA),
                dirtyInitial.toList(),
                "A.kt should be marked dirty when no-source output NoSrc1.kt that it referenced disappears"
            )
            ic.closeFiles()
        }
    }

    @Test
    fun testRelativeBaseDirWithNoSourceWildcard(@TempDir tempDir: File) {
        val cwd = File(".").canonicalFile
        val absBaseDir = tempDir.canonicalFile
        val relBaseDir = absBaseDir.relativeToOrNull(cwd) ?: return

        val cachesDir = File(relBaseDir, "caches").apply { mkdirs() }
        val outputDir = File(relBaseDir, "outputs").apply { mkdirs() }
        val classesDir = File(outputDir, "classes").apply { mkdirs() }
        val javaDir = File(outputDir, "java").apply { mkdirs() }
        val kotlinDir = File(outputDir, "kotlin").apply { mkdirs() }
        val resourcesDir = File(outputDir, "resources").apply { mkdirs() }

        val anyChanges = AnyChanges(relBaseDir)
        val anyChangesRel = File(anyChanges.filePath).relativeTo(relBaseDir)
        val symbolStorage = FakeLookupStorage()
        val classStorage = FakeLookupStorage()

        val codeGen = CodeGeneratorImpl(
            classesDir, { javaDir }, kotlinDir, resourcesDir,
            relBaseDir, anyChanges, emptyList(), true
        )
        val ic = TestIncrementalContext(
            anyChangesRel, relBaseDir, cachesDir, outputDir,
            emptyList(), emptyList(), symbolStorage, classStorage
        )
        ic.calcDirtyFiles(emptyList())
        codeGen.createNewFile(Dependencies(false), "a", "RelNoSrc", "kt").close()
        ic.updateCachesAndOutputs(emptyList(), codeGen.sourceToOutputs)
        ic.closeFiles()

        assertTrue(
            File(cachesDir, "backups/kotlin/a/RelNoSrc.kt").exists(),
            "No-source output should be backed up when baseDir is a relative File"
        )
    }
}
