
package ua.kropilive.app.updates

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * Результат подготовки установки обновления.
 */
sealed class ApkInstallResult {

    data object InstallerOpened : ApkInstallResult()

    data object PermissionRequired : ApkInstallResult()

    data class Error(
        val message: String
    ) : ApkInstallResult()
}

/**
 * Системная установка обновлений Кроп Live.
 *
 * Не устанавливает APK без согласия пользователя.
 * Android проверяет подпись при обновлении приложения.
 */
class GitHubApkInstaller(
    private val context: Context
) {

    companion object {
        const val FILE_PROVIDER_AUTHORITY_SUFFIX =
            ".updateprovider"
    }

    /**
     * Проверяем разрешение на установку APK
     * из нашего приложения.
     */
    fun canInstallPackages(): Boolean {
        return if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {
            context.packageManager
                .canRequestPackageInstalls()
        } else {
            true
        }
    }

    /**
     * Открываем системные настройки разрешения.
     */
    fun openInstallPermissionSettings(): Boolean {
        return try {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse(
                    "package:${context.packageName}"
                )
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Проверяем APK перед передачей Android.
     *
     * APK должен:
     * - существовать;
     * - находиться в нашем каталоге обновлений;
     * - иметь тот же applicationId;
     * - иметь versionCode выше установленного.
     */
    fun validateApk(apkFile: File): String? {
        if (!apkFile.isFile || apkFile.length() == 0L) {
            return "Файл оновлення не знайдено"
        }

        val expectedDirectory = File(
            context.cacheDir,
            "updates"
        ).canonicalFile

        val actualFile = apkFile.canonicalFile

        if (
            actualFile.parentFile != expectedDirectory ||
            actualFile.name != "update.apk"
        ) {
            return "Недопустиме розташування APK"
        }

        val packageManager = context.packageManager

        @Suppress("DEPRECATION")
        val archiveInfo = packageManager
            .getPackageArchiveInfo(
                actualFile.absolutePath,
                0
            ) ?: return "Не вдалося прочитати APK"

        if (archiveInfo.packageName != context.packageName) {
            return "APK належить іншому застосунку"
        }

        val installedInfo = if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {
            packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(
                context.packageName,
                0
            )
        }

        val installedCode =
            if (Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.P
            ) {
                installedInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                installedInfo.versionCode.toLong()
            }

        val archiveCode =
            if (Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.P
            ) {
                archiveInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                archiveInfo.versionCode.toLong()
            }

        if (archiveCode <= installedCode) {
            return "Версія APK не новіша за встановлену"
        }

        return null
    }

    /**
     * Открываем системный установщик Android.
     *
     * FileProvider предоставляет временный
     * доступ к скачанному APK.
     */
    fun installApk(
        apkFile: File
    ): ApkInstallResult {

        return try {
            val validationError = validateApk(apkFile)

            if (validationError != null) {
                return ApkInstallResult.Error(
                    validationError
                )
            }

            if (!canInstallPackages()) {
                return ApkInstallResult.PermissionRequired
            }

            val apkUri = FileProvider.getUriForFile(
                context,
                context.packageName +
                        FILE_PROVIDER_AUTHORITY_SUFFIX,
                apkFile
            )

            val installIntent = Intent(
                Intent.ACTION_VIEW
            ).apply {
                setDataAndType(
                    apkUri,
                    "application/vnd.android.package-archive"
                )

                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )

                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                )
            }

            context.startActivity(installIntent)

            ApkInstallResult.InstallerOpened

        } catch (_: ActivityNotFoundException) {
            ApkInstallResult.Error(
                "Системний інсталятор недоступний"
            )
        } catch (e: Exception) {
            ApkInstallResult.Error(
                e.localizedMessage
                    ?: "Не вдалося відкрити інсталятор"
            )
        }
    }
}
