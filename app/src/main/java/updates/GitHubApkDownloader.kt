
package ua.kropilive.app.updates

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Результат скачивания APK.
 */
sealed class ApkDownloadResult {

    data class Success(
        val apkFile: File
    ) : ApkDownloadResult()

    data class Error(
        val message: String
    ) : ApkDownloadResult()
}

/**
 * Загрузчик обновлений Кроп Live.
 *
 * Скачивает APK из GitHub Releases
 * во временное хранилище приложения.
 */
class GitHubApkDownloader(
    context: Context
) {

    companion object {
        private const val APK_FILE_NAME = "update.apk"
        private const val TEMP_FILE_NAME = "update.apk.part"

        private const val MAX_APK_SIZE =
            250L * 1024L * 1024L

        private const val BUFFER_SIZE = 8192
    }

    private val updateDirectory = File(
        context.applicationContext.cacheDir,
        "updates"
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * Скачивает APK.
     *
     * onProgress получает значение от 0 до 100.
     * Если размер файла неизвестен, передаётся -1.
     *
     * Callback вызывается на фоновом потоке.
     */
    suspend fun downloadApk(
        downloadUrl: String,
        onProgress: (Int) -> Unit = {}
    ): ApkDownloadResult = withContext(Dispatchers.IO) {

        val tempFile = File(
            updateDirectory,
            TEMP_FILE_NAME
        )

        val apkFile = File(
            updateDirectory,
            APK_FILE_NAME
        )

        try {
            if (!isAllowedDownloadUrl(downloadUrl)) {
                return@withContext ApkDownloadResult.Error(
                    "Недопустиме посилання на оновлення"
                )
            }

            if (
                !updateDirectory.exists() &&
                !updateDirectory.mkdirs()
            ) {
                return@withContext ApkDownloadResult.Error(
                    "Не вдалося створити папку оновлень"
                )
            }

            // Удаляем предыдущие временные файлы.
            tempFile.delete()
            apkFile.delete()

            onProgress(0)

            val request = Request.Builder()
                .url(downloadUrl)
                .header(
                    "User-Agent",
                    "KropLive-Android"
                )
                .get()
                .build()

            val call = client.newCall(request)

            val job = currentCoroutineContext()[Job]

            val cancellationHandle =
                job?.invokeOnCompletion {
                    if (job.isCancelled) {
                        call.cancel()
                    }
                }

            try {
                call.execute().use { response ->

                    if (!response.isSuccessful) {
                        throw IOException(
                            "GitHub HTTP ${response.code}"
                        )
                    }

                    val body = response.body
                        ?: throw IOException(
                            "Порожня відповідь сервера"
                        )

                    val totalBytes = body.contentLength()

                    if (totalBytes > MAX_APK_SIZE) {
                        throw IOException(
                            "APK перевищує допустимий розмір"
                        )
                    }

                    var downloadedBytes = 0L
                    var lastProgress = -1

                    body.byteStream().use { input ->
                        tempFile.outputStream().buffered().use {
                                output ->

                            val buffer = ByteArray(BUFFER_SIZE)

                            while (true) {
                                currentCoroutineContext()
                                    .ensureActiveForDownload()

                                val bytesRead = input.read(buffer)

                                if (bytesRead == -1) break
                                if (bytesRead == 0) continue

                                downloadedBytes += bytesRead

                                if (
                                    downloadedBytes > MAX_APK_SIZE
                                ) {
                                    throw IOException(
                                        "APK перевищує допустимий розмір"
                                    )
                                }

                                output.write(
                                    buffer,
                                    0,
                                    bytesRead
                                )

                                if (totalBytes > 0L) {
                                    val progress = (
                                            downloadedBytes * 100L /
                                                    totalBytes
                                            ).toInt().coerceIn(0, 100)

                                    if (progress != lastProgress) {
                                        lastProgress = progress
                                        onProgress(progress)
                                    }
                                } else if (lastProgress != -1) {
                                    lastProgress = -1
                                    onProgress(-1)
                                }
                            }

                            output.flush()
                        }
                    }

                    if (downloadedBytes == 0L) {
                        throw IOException(
                            "Завантажений файл порожній"
                        )
                    }

                    if (
                        totalBytes >= 0L &&
                        downloadedBytes != totalBytes
                    ) {
                        throw IOException(
                            "Файл завантажено не повністю"
                        )
                    }
                }
            } finally {
                cancellationHandle?.dispose()
            }

            currentCoroutineContext()
                .ensureActiveForDownload()

            if (!isValidApkArchive(tempFile)) {
                throw IOException(
                    "Завантажений файл не є коректним APK"
                )
            }

            if (!tempFile.renameTo(apkFile)) {
                throw IOException(
                    "Не вдалося підготувати APK"
                )
            }

            onProgress(100)

            ApkDownloadResult.Success(apkFile)

        } catch (e: CancellationException) {
            tempFile.delete()
            apkFile.delete()
            throw e

        } catch (e: Exception) {
            tempFile.delete()
            apkFile.delete()

            if (
                currentCoroutineContext()[Job]
                    ?.isCancelled == true
            ) {
                throw CancellationException(
                    "Завантаження скасовано"
                ).apply {
                    initCause(e)
                }
            }

            ApkDownloadResult.Error(
                e.localizedMessage
                    ?: "Помилка завантаження APK"
            )
        }
    }

    /**
     * Разрешаем скачивание только из Releases
     * нашего GitHub-репозитория.
     */
    private fun isAllowedDownloadUrl(
        url: String
    ): Boolean {
        return try {
            val parsed = url.toHttpUrl()

            parsed.scheme == "https" &&
                    parsed.host == "github.com" &&
                    parsed.encodedPath.startsWith(
                        "/sergeymortes/KropLive/" +
                                "releases/download/"
                    )
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Проверяем ZIP-структуру APK
     * и наличие AndroidManifest.xml.
     *
     * Это проверка целостности архива,
     * а не проверка цифровой подписи.
     */
    private fun isValidApkArchive(file: File): Boolean {
        if (!file.isFile || file.length() == 0L) {
            return false
        }

        return try {
            ZipFile(file).use { zip ->
                zip.getEntry("AndroidManifest.xml") != null &&
                        zip.getEntry("classes.dex") != null
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Удаляет временные APK после обновления.
     *
     * Вызывать при следующем запуске приложения,
     * когда установщик больше не использует файл.
     */
    fun cleanupDownloadedApk() {
        File(updateDirectory, TEMP_FILE_NAME).delete()
        File(updateDirectory, APK_FILE_NAME).delete()
    }
}

/**
 * Проверка отмены корутины.
 */
private fun kotlin.coroutines.CoroutineContext
        .ensureActiveForDownload() {
    if (this[Job]?.isActive == false) {
        throw CancellationException(
            "Завантаження скасовано"
        )
    }
}
