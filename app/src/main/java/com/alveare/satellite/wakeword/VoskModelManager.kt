package com.alveare.satellite.wakeword

import android.content.Context
import android.util.Log
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

sealed class VoskModelStatus {
    object NotInstalled : VoskModelStatus()
    data class Downloading(val progressPercent: Int, val bytesRead: Long, val totalBytes: Long) : VoskModelStatus()
    object Unpacking : VoskModelStatus()
    data class Ready(val modelDir: File) : VoskModelStatus()
    data class Error(val message: String) : VoskModelStatus()
}

/**
 * Manages the offline Vosk Italian Kaldi model for "Ehi Alveare" wake phrase recognition.
 * Handles validation of required components (conf/model.conf, am/final.mdl, graph files),
 * streaming download from official Alphacephei source (~49.6MB), atomic staging extraction,
 * zip safety checks (zip-slip & decompression bounds), progress reporting, cancellation, and error handling.
 */
object VoskModelManager {
    private const val TAG = "VoskModelManager"

    const val MODEL_NAME = "vosk-model-small-it-0.22"
    const val OFFICIAL_DOWNLOAD_URL = "https://alphacephei.com/vosk/models/vosk-model-small-it-0.22.zip"
    const val EXPECTED_SIZE_BYTES = 49_665_141L // Verified via HTTP HEAD on official Alphacephei distribution

    private val isDownloading = AtomicBoolean(false)
    private val downloadGeneration = AtomicInteger(0)
    private var downloadThread: Thread? = null
    @Volatile private var activeCall: Call? = null

    fun isModelDirectoryValid(dir: File?): Boolean {
        if (dir == null || !dir.exists() || !dir.isDirectory) return false

        // 1. conf/model.conf must exist and be a non-empty regular file
        val confFile = File(dir, "conf/model.conf")
        if (!confFile.isFile || confFile.length() <= 0) return false

        // 2. am/final.mdl must exist and be a non-empty regular file
        val amFile = File(dir, "am/final.mdl")
        if (!amFile.isFile || amFile.length() <= 0) return false

        // 3. graph must exist and contain model graph files
        val graphDir = File(dir, "graph")
        if (!graphDir.isDirectory) return false
        val graphFiles = graphDir.listFiles()
        if (graphFiles.isNullOrEmpty()) return false

        return true
    }

    fun findValidModelDir(baseDir: File): File? {
        if (isModelDirectoryValid(baseDir)) return baseDir

        val children = baseDir.listFiles() ?: return null
        for (child in children) {
            if (child.isDirectory && isModelDirectoryValid(child)) {
                return child
            }
        }
        return null
    }

    fun getModelRoot(context: Context): File {
        val dir = File(context.filesDir, "models")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getInstalledModelDir(context: Context): File? {
        val root = getModelRoot(context)
        val candidate = File(root, MODEL_NAME)
        return findValidModelDir(candidate) ?: findValidModelDir(root)
    }

    fun isModelInstalled(context: Context): Boolean {
        return getInstalledModelDir(context) != null
    }

    fun cancelDownload() {
        downloadGeneration.incrementAndGet()
        if (isDownloading.getAndSet(false)) {
            try {
                activeCall?.cancel()
            } catch (ignored: Exception) {}
            activeCall = null
            downloadThread?.interrupt()
            downloadThread = null
        }
    }

    fun downloadAndInstall(
        context: Context,
        onProgress: (Int) -> Unit,
        onStatusChanged: (VoskModelStatus) -> Unit,
        onComplete: (File) -> Unit,
        onError: (String) -> Unit
    ) {
        if (isDownloading.getAndSet(true)) {
            onError("Download già in corso.")
            return
        }

        val currentGen = downloadGeneration.incrementAndGet()
        val appContext = context.applicationContext

        downloadThread = Thread({
            val root = getModelRoot(appContext)
            val tempZip = File(root, "$MODEL_NAME.$currentGen.download.zip")
            val stagingDir = File(root, ".staging_${System.currentTimeMillis()}_$currentGen")
            val targetDir = File(root, MODEL_NAME)

            var response: Response? = null

            try {
                onStatusChanged(VoskModelStatus.Downloading(0, 0, EXPECTED_SIZE_BYTES))

                val client = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .build()

                val request = Request.Builder().url(OFFICIAL_DOWNLOAD_URL).build()
                val call = client.newCall(request)
                activeCall = call
                val resp = call.execute()
                response = resp

                if (!resp.isSuccessful) {
                    throw IllegalStateException("Errore HTTP ${resp.code}: ${resp.message}")
                }

                val body = resp.body ?: throw IllegalStateException("Risposta vuota dal server Vosk")
                val totalLength = if (body.contentLength() > 0) body.contentLength() else EXPECTED_SIZE_BYTES

                // 1. Download to temporary zip file
                body.byteStream().use { input ->
                    FileOutputStream(tempZip).use { output ->
                        val buffer = ByteArray(32768)
                        var bytesRead: Long = 0
                        var lastProgress = 0
                        var count: Int

                        while (input.read(buffer).also { count = it } != -1) {
                            if (!isDownloading.get() || downloadGeneration.get() != currentGen) {
                                tempZip.delete()
                                return@Thread
                            }
                            output.write(buffer, 0, count)
                            bytesRead += count

                            val progress = ((bytesRead * 100) / totalLength).toInt().coerceIn(0, 100)
                            if (progress != lastProgress) {
                                lastProgress = progress
                                onProgress(progress)
                                onStatusChanged(VoskModelStatus.Downloading(progress, bytesRead, totalLength))
                            }
                        }
                        output.flush()
                    }
                }

                if (downloadGeneration.get() != currentGen) {
                    tempZip.delete()
                    return@Thread
                }

                // 2. Unpack zip into isolated temporary staging directory
                onStatusChanged(VoskModelStatus.Unpacking)
                if (stagingDir.exists()) stagingDir.deleteRecursively()
                stagingDir.mkdirs()

                unzipSafely(tempZip, stagingDir)
                tempZip.delete()

                if (downloadGeneration.get() != currentGen) {
                    stagingDir.deleteRecursively()
                    return@Thread
                }

                // 3. Strict validation before finalizing installation
                val validDir = findValidModelDir(stagingDir)
                if (validDir == null || !isModelDirectoryValid(validDir)) {
                    throw IllegalStateException("Modello Vosk non valido dopo l'estrazione: componenti conf/am/graph mancanti o corrotti.")
                }

                // 4. Atomic installation: replace targetDir cleanly
                if (targetDir.exists()) {
                    targetDir.deleteRecursively()
                }

                val renamed = validDir.renameTo(targetDir)
                if (!renamed) {
                    validDir.copyRecursively(targetDir, overwrite = true)
                    validDir.deleteRecursively()
                }
                stagingDir.deleteRecursively()

                val finalValidDir = findValidModelDir(targetDir) ?: targetDir
                if (!isModelDirectoryValid(finalValidDir)) {
                    throw IllegalStateException("Modello non valido nella directory finale.")
                }

                isDownloading.set(false)
                onStatusChanged(VoskModelStatus.Ready(finalValidDir))
                onComplete(finalValidDir)

            } catch (e: InterruptedException) {
                tempZip.delete()
                stagingDir.deleteRecursively()
                isDownloading.set(false)
                Log.w(TAG, "Download cancellato dall'utente")
            } catch (e: Throwable) {
                tempZip.delete()
                stagingDir.deleteRecursively()
                isDownloading.set(false)
                val err = e.localizedMessage ?: e.message ?: "Errore sconosciuto durante il download"
                Log.e(TAG, "Errore installazione modello Vosk: $err", e)
                onStatusChanged(VoskModelStatus.Error(err))
                onError(err)
            } finally {
                try {
                    response?.close()
                } catch (ignored: Exception) {}
                activeCall = null
            }
        }, "VoskModelDownloadThread").apply {
            start()
        }
    }

    fun unzipSafely(zipFile: File, destDir: File, maxBytes: Long = 250_000_000L) {
        val zipInputStream = ZipInputStream(zipFile.inputStream().buffered())
        val buffer = ByteArray(16384)
        var totalBytesExtracted = 0L
        val canonicalDestDir = destDir.canonicalPath

        zipInputStream.use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                val newFile = File(destDir, entry.name)
                val canonicalFilePath = newFile.canonicalPath

                // Zip Slip vulnerability protection
                if (!canonicalFilePath.startsWith(canonicalDestDir + File.separator) && canonicalFilePath != canonicalDestDir) {
                    throw SecurityException("Tentativo di zip traversal rilevato: ${entry.name}")
                }

                if (entry.isDirectory) {
                    newFile.mkdirs()
                } else {
                    newFile.parentFile?.mkdirs()
                    FileOutputStream(newFile).use { fos ->
                        var len: Int
                        while (zis.read(buffer).also { len = it } > 0) {
                            totalBytesExtracted += len
                            if (totalBytesExtracted > maxBytes) {
                                throw SecurityException("Archivio zip supera la dimensione massima consentita ($maxBytes bytes)")
                            }
                            fos.write(buffer, 0, len)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    fun deleteModel(context: Context): Boolean {
        cancelDownload()
        val dir = getInstalledModelDir(context)
        return dir?.deleteRecursively() ?: false
    }
}
