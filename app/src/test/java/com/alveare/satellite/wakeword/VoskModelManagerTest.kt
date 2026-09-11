package com.alveare.satellite.wakeword

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class VoskModelManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testModelValidationEmptyDir() {
        val emptyDir = tempFolder.newFolder("empty_model")
        assertFalse(VoskModelManager.isModelDirectoryValid(emptyDir))
    }

    @Test
    fun testModelValidationEmptyDirectoriesRejected() {
        val modelDir = tempFolder.newFolder("empty_subdirs_model")
        File(modelDir, "conf").mkdirs()
        File(modelDir, "am").mkdirs()
        File(modelDir, "graph").mkdirs()

        assertFalse("Empty conf and am directories MUST NOT be accepted as valid model",
            VoskModelManager.isModelDirectoryValid(modelDir))
    }

    @Test
    fun testModelValidationZeroByteFilesRejected() {
        val modelDir = tempFolder.newFolder("zero_byte_model")
        File(modelDir, "conf").mkdirs()
        File(modelDir, "conf/model.conf").createNewFile() // 0 bytes
        File(modelDir, "am").mkdirs()
        File(modelDir, "am/final.mdl").createNewFile() // 0 bytes
        File(modelDir, "graph").mkdirs()
        File(modelDir, "graph/Gr.fst").createNewFile()

        assertFalse("0-byte files MUST be rejected", VoskModelManager.isModelDirectoryValid(modelDir))
    }

    @Test
    fun testModelValidationCompleteDir() {
        val modelDir = tempFolder.newFolder("vosk-model-small-it-0.22")
        File(modelDir, "conf").mkdirs()
        File(modelDir, "conf/model.conf").writeText("sample_rate = 16000")
        File(modelDir, "am").mkdirs()
        File(modelDir, "am/final.mdl").writeText("dummy-acoustic-model")
        File(modelDir, "graph").mkdirs()
        File(modelDir, "graph/Gr.fst").writeText("dummy-graph")

        assertTrue(VoskModelManager.isModelDirectoryValid(modelDir))
    }

    @Test
    fun testFindModelInExtractedFolder() {
        val parent = tempFolder.newFolder("unzipped_root")
        val inner = File(parent, "vosk-model-small-it-0.22")
        inner.mkdirs()
        File(inner, "conf").mkdirs()
        File(inner, "conf/model.conf").writeText("sample_rate = 16000")
        File(inner, "am").mkdirs()
        File(inner, "am/final.mdl").writeText("dummy-acoustic-model")
        File(inner, "graph").mkdirs()
        File(inner, "graph/Gr.fst").writeText("dummy-graph")

        val found = VoskModelManager.findValidModelDir(parent)
        assertNotNull(found)
        assertEquals(inner.absolutePath, found?.absolutePath)
    }

    @Test
    fun testZipSlipTraversalRejected() {
        val zipFile = tempFolder.newFile("malicious.zip")
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            zos.putNextEntry(ZipEntry("../../evil.txt"))
            zos.write("malicious".toByteArray())
            zos.closeEntry()
        }

        val destDir = tempFolder.newFolder("extract_dest")
        try {
            VoskModelManager.unzipSafely(zipFile, destDir)
            fail("Expected SecurityException on zip traversal attempt")
        } catch (e: SecurityException) {
            assertTrue("Should report zip traversal", e.message?.contains("traversal", ignoreCase = true) == true)
        }
    }

    @Test
    fun testZipDecompressionBombRejected() {
        val zipFile = tempFolder.newFile("bomb.zip")
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            zos.putNextEntry(ZipEntry("huge.dat"))
            val zeros = ByteArray(1024)
            for (i in 0 until 50) { // 50 KB uncompressed
                zos.write(zeros)
            }
            zos.closeEntry()
        }

        val destDir = tempFolder.newFolder("extract_dest_bomb")
        try {
            // Set maxBytes very low (10 KB) to verify limit enforcement
            VoskModelManager.unzipSafely(zipFile, destDir, maxBytes = 10_000L)
            fail("Expected SecurityException on oversized zip")
        } catch (e: SecurityException) {
            assertTrue("Should report size limit exceeded", e.message?.contains("massima", ignoreCase = true) == true)
        }
    }
}
