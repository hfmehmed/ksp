/*
 * Copyright 2026 Google LLC
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
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
package com.google.devtools.ksp.gradle

import com.google.common.truth.Truth.assertThat
import org.jetbrains.kotlin.buildtools.api.SourcesChanges
import org.jetbrains.kotlin.gradle.internal.kapt.incremental.KaptClasspathChanges
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FindClasspathChangesTest {
    @Rule
    @JvmField
    val tmpDir = TemporaryFolder()

    private lateinit var cacheDir: File
    private lateinit var libJar: File
    private lateinit var processorJar: File

    @Before
    fun setUp() {
        cacheDir = tmpDir.newFolder("cache")
        libJar = tmpDir.newFile("lib.jar")
        processorJar = tmpDir.newFile("processor.jar")
    }

    @Test
    fun unknownChangesClearsCorruptCacheAndWritesFreshSnapshot() {
        cacheDir.resolve("classpath-structure.bin").writeText("corrupt-data")
        val staleMarker = cacheDir.resolve("stale-marker.txt").apply { writeText("stale") }

        val changes = findClasspathChanges(
            changes = SourcesChanges.Unknown,
            cacheDir = cacheDir,
            allDataFiles = emptySet(),
            libs = listOf(libJar),
            processorCP = listOf(processorJar),
        )

        assertThat(changes).isEqualTo(KaptClasspathChanges.Unknown)
        assertThat(staleMarker.exists()).isFalse()
        assertThat(cacheDir.resolve("classpath-structure.bin").isFile).isTrue()
        assertThat(cacheDir.resolve("classpath-entries.bin").isFile).isTrue()
        assertThat(cacheDir.resolve("ap-classpath-entries.bin").isFile).isTrue()
    }

    @Test
    fun emptyKnownChangesSkipsSnapshotLoadAndWriteWhenClasspathUnchanged() {
        findClasspathChanges(
            changes = SourcesChanges.Unknown,
            cacheDir = cacheDir,
            allDataFiles = emptySet(),
            libs = listOf(libJar),
            processorCP = listOf(processorJar),
        )
        // Corrupt the structure file on disk: if the fast-path skips loading/writing it,
        // this call will succeed and leave the file untouched.
        val structureFile = cacheDir.resolve("classpath-structure.bin")
        structureFile.writeText("corrupt-if-loaded")

        val changes = findClasspathChanges(
            changes = SourcesChanges.Known(
                modifiedFiles = listOf(File("Foo.kt"), File("Bar.java")),
                removedFiles = emptyList(),
            ),
            cacheDir = cacheDir,
            allDataFiles = emptySet(),
            libs = listOf(libJar),
            processorCP = listOf(processorJar),
        )

        assertThat(changes).isInstanceOf(KaptClasspathChanges.Known::class.java)
        assertThat((changes as KaptClasspathChanges.Known).names).isEmpty()
        assertThat(structureFile.readText()).isEqualTo("corrupt-if-loaded")
    }

    @Test
    fun emptyKnownChangesFallsBackWhenLibsOrProcessorsChanged() {
        findClasspathChanges(
            changes = SourcesChanges.Unknown,
            cacheDir = cacheDir,
            allDataFiles = emptySet(),
            libs = listOf(libJar),
            processorCP = listOf(processorJar),
        )

        val updatedLibs = listOf(libJar, tmpDir.newFile("lib2.jar"))
        val changesAfterLibChange = findClasspathChanges(
            changes = SourcesChanges.Known(emptyList(), emptyList()),
            cacheDir = cacheDir,
            allDataFiles = emptySet(),
            libs = updatedLibs,
            processorCP = listOf(processorJar),
        )
        assertThat(changesAfterLibChange).isEqualTo(KaptClasspathChanges.Unknown)

        val changesAfterProcessorChange = findClasspathChanges(
            changes = SourcesChanges.Known(emptyList(), emptyList()),
            cacheDir = cacheDir,
            allDataFiles = emptySet(),
            libs = updatedLibs,
            processorCP = listOf(processorJar, tmpDir.newFile("processor2.jar")),
        )
        assertThat(changesAfterProcessorChange).isEqualTo(KaptClasspathChanges.Unknown)
    }
}
