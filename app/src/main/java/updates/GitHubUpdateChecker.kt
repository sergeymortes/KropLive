
package ua.kropilive.app.updates

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Информация о доступном обновлении Кроп Live.
 */
data class AppUpdateInfo(
    val currentVersion: String,
    val latestVersion: String,
    val downloadUrl: String,
    val releaseNotes: String,
    val apkFileName: String,
    val tagName: String
)

/**
 * Результат проверки обновлений.
 */
sealed class UpdateCheckResult {

    data class UpdateAvailable(
        val info: AppUpdateInfo
    ) : UpdateCheckResult()

    data object UpToDate : UpdateCheckResult()

    data object NoPublishedRelease : UpdateCheckResult()

    data class Error(
        val message: String
    ) : UpdateCheckResult()
}

/**
 * Проверка GitHub Releases.
 */
class GitHubUpdateChecker(
    private val context: Context
) {

    companion object {
        private const val RELEASE_API =
            "https://api.github.com/repos/" +
                    "sergeymortes/KropLive/releases/latest"

        private const val RELEASE_DOWNLOAD_PREFIX =
            "https://github.com/" +
                    "sergeymortes/KropLive/releases/download/"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun checkForUpdates(): UpdateCheckResult =
        withContext(Dispatchers.IO) {
            try {
                performUpdateCheck()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UpdateCheckResult.Error(
                    e.localizedMessage
                        ?: "Помилка перевірки оновлень"
                )
            }
        }

    private fun performUpdateCheck(): UpdateCheckResult {
        val currentVersion = getInstalledVersion()

        val request = Request.Builder()
            .url(RELEASE_API)
            .header(
                "Accept",
                "application/vnd.github+json"
            )
            .header(
                "X-GitHub-Api-Version",
                "2022-11-28"
            )
            .header(
                "User-Agent",
                "KropLive-Android"
            )
            .get()
            .build()

        client.newCall(request).execute().use { response ->

            if (response.code == 404) {
                return UpdateCheckResult.NoPublishedRelease
            }

            if (!response.isSuccessful) {
                return UpdateCheckResult.Error(
                    "GitHub HTTP ${response.code}"
                )
            }

            val responseBody = response.body?.string()

            if (responseBody.isNullOrBlank()) {
                return UpdateCheckResult.Error(
                    "Порожня відповідь GitHub"
                )
            }

            val release = JSONObject(responseBody)

            val tagName = release
                .optString("tag_name")
                .trim()

            val latestVersion = tagName
                .removePrefix("v")
                .removePrefix("V")

            val comparison = compareVersions(
                latestVersion,
                currentVersion
            )

            if (comparison == null) {
                return UpdateCheckResult.Error(
                    "Невірний формат версії: $tagName"
                )
            }

            if (comparison <= 0) {
                return UpdateCheckResult.UpToDate
            }

            val assets = release.optJSONArray("assets")

            if (assets == null || assets.length() == 0) {
                return UpdateCheckResult.Error(
                    "У релізі немає APK-файлу"
                )
            }

            var apkUrl: String? = null
            var apkName: String? = null

            for (index in 0 until assets.length()) {
                val asset = assets.optJSONObject(index)
                    ?: continue

                val name = asset.optString("name")
                val url = asset.optString(
                    "browser_download_url"
                )

                if (
                    name.endsWith(
                        ".apk",
                        ignoreCase = true
                    ) &&
                    url.startsWith(RELEASE_DOWNLOAD_PREFIX)
                ) {
                    apkUrl = url
                    apkName = name
                    break
                }
            }

            if (apkUrl.isNullOrBlank() ||
                apkName.isNullOrBlank()
            ) {
                return UpdateCheckResult.Error(
                    "APK-файл не знайдено у релізі"
                )
            }

            return UpdateCheckResult.UpdateAvailable(
                AppUpdateInfo(
                    currentVersion = currentVersion,
                    latestVersion = latestVersion,
                    downloadUrl = apkUrl,
                    releaseNotes = release.optString("body"),
                    apkFileName = apkName,
                    tagName = tagName
                )
            )
        }
    }

    /**
     * Получаем установленную версию приложения.
     */
    private fun getInstalledVersion(): String {

        val packageInfo = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        ) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(
                context.packageName,
                0
            )
        }

        return packageInfo.versionName ?: "0.0.0"
    }

    /**
     * Сравнение версий:
     * 1.0.0, 1.1.0, 1.2.3
     */
    private fun compareVersions(
        latest: String,
        current: String
    ): Int? {

        fun parse(version: String): List<Long>? {
            val clean = version.trim()
                .removePrefix("v")
                .removePrefix("V")

            if (!clean.matches(Regex("\\d+(\\.\\d+)*"))) {
                return null
            }

            return clean.split(".").map {
                it.toLongOrNull() ?: return null
            }
        }

        val latestParts = parse(latest) ?: return null
        val currentParts = parse(current) ?: return null

        val maxSize = maxOf(
            latestParts.size,
            currentParts.size
        )

        for (index in 0 until maxSize) {
            val latestPart =
                latestParts.getOrElse(index) { 0L }

            val currentPart =
                currentParts.getOrElse(index) { 0L }

            if (latestPart > currentPart) return 1
            if (latestPart < currentPart) return -1
        }

        return 0
    }
}
