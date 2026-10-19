package ua.kropilive.app.updates

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Состояние загрузки обновления.
 */
sealed interface UpdateDownloadState {
    data object Idle : UpdateDownloadState
    data class Downloading(
        val progress: Int
    ) : UpdateDownloadState
    data object Installing : UpdateDownloadState
    data class Failed(
        val message: String
    ) : UpdateDownloadState
}

/**
 * Диалог обновления Кроп Live.
 *
 * Цвета автоматически берутся из MaterialTheme.
 * Поддерживает светлую и тёмную темы.
 */
@Composable
fun UpdateDialog(
    updateInfo: AppUpdateInfo,
    downloadState: UpdateDownloadState,
    onUpdateClick: () -> Unit,
    onDismiss: () -> Unit
) {
    val isDownloading =
        downloadState is UpdateDownloadState.Downloading

    val isInstalling =
        downloadState is UpdateDownloadState.Installing

    val isBusy = isDownloading || isInstalling

    AlertDialog(
        onDismissRequest = {
            if (!isBusy) onDismiss()
        },
        icon = {
            Icon(
                imageVector = Icons.Rounded.SystemUpdate,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                text = "Доступне оновлення",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "Для Кроп Live доступна нова версія.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    VersionCard(
                        label = "Поточна версія",
                        version = updateInfo.currentVersion,
                        modifier = Modifier.weight(1f)
                    )

                    VersionCard(
                        label = "Нова версія",
                        version = updateInfo.latestVersion,
                        modifier = Modifier.weight(1f),
                        isNew = true
                    )
                }

                if (updateInfo.releaseNotes.isNotBlank()) {
                    HorizontalDivider()

                    Text(
                        text = "Що нового",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )

                    Text(
                        text = updateInfo.releaseNotes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                when (downloadState) {
                    is UpdateDownloadState.Downloading -> {
                        DownloadProgress(
                            progress = downloadState.progress
                        )
                    }

                    UpdateDownloadState.Installing -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .width(22.dp)
                                    .height(22.dp),
                                strokeWidth = 2.dp
                            )

                            Text(
                                text = "Підготовка до встановлення...",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    is UpdateDownloadState.Failed -> {
                        Text(
                            text = downloadState.message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    UpdateDownloadState.Idle -> Unit
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onUpdateClick,
                enabled = !isBusy
            ) {
                if (isDownloading) {
                    Text("Завантаження...")
                } else if (isInstalling) {
                    Text("Встановлення...")
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Download,
                        contentDescription = null
                    )

                    Spacer(Modifier.width(8.dp))

                    Text(
                        if (downloadState is UpdateDownloadState.Failed)
                            "Повторити"
                        else
                            "Оновити"
                    )
                }
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                enabled = !isBusy
            ) {
                Text("Пізніше")
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
private fun VersionCard(
    label: String,
    version: String,
    modifier: Modifier = Modifier,
    isNew: Boolean = false
) {
    val backgroundColor = if (isNew) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }

    val versionColor = if (isNew) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Surface(
        modifier = modifier,
        color = backgroundColor,
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = versionColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = version,
                style = MaterialTheme.typography.titleMedium,
                color = versionColor,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun DownloadProgress(
    progress: Int
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Завантаження оновлення",
                style = MaterialTheme.typography.labelMedium
            )

            if (progress >= 0) {
                Text(
                    text = "${progress.coerceIn(0, 100)}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        if (progress >= 0) {
            LinearProgressIndicator(
                progress = {
                    progress.coerceIn(0, 100) / 100f
                },
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
