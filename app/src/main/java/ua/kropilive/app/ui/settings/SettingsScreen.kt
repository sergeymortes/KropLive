package ua.kropilive.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ua.kropilive.app.KiroeAddressPicker
import ua.kropilive.app.data.repository.AddressRepository
import ua.kropilive.app.data.repository.UserAddress
import ua.kropilive.app.ui.theme.ThemeMode
import ua.kropilive.app.background.NotificationPreferences
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit
) {
    val context = LocalContext.current.applicationContext

    val addressRepository = remember(context) {
        AddressRepository(context)
    }

    val savedAddress by addressRepository.address.collectAsState(
        initial = UserAddress()
    )

    var showAddressPicker by remember {
        mutableStateOf(false)
    }

    var showNotificationSettings by remember { mutableStateOf(false) }
    var showThemeDialog by remember {
        mutableStateOf(false)
    }

    var infoDialog by remember {
        mutableStateOf<String?>(null)
    }

    val hasAddress = savedAddress.isFilled
    val canRefreshQueue = savedAddress.hasFullAddress

    val themeName = when (themeMode) {
        ThemeMode.SYSTEM -> "Як у системі"
        ThemeMode.LIGHT -> "Світла"
        ThemeMode.DARK -> "Темна"
    }

    if (showNotificationSettings) {
        NotificationSettingsScreen(onBack = { showNotificationSettings = false })
        return
    }

    if (showAddressPicker) {
        KiroeAddressPicker(
            onBack = {
                showAddressPicker = false
            },
            onSaved = {
                showAddressPicker = false
            }
        )

        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {

        Text(
            text = "Налаштування",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(2.dp))

        SettingsSectionTitle("МОЯ АДРЕСА")

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = null,
                            modifier = Modifier.padding(12.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Домашня адреса",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )

                        if (hasAddress) {
                            if (savedAddress.cityName.isNotBlank()) {
                                Text(
                                    text = savedAddress.cityName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Text(
                                text = "${savedAddress.street}, ${savedAddress.building}",
                                style = MaterialTheme.typography.bodyMedium
                            )

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(15.dp)
                                )

                                Text(
                                    text = if (canRefreshQueue) {
                                        "Адресу підтверджено"
                                    } else {
                                        "Стара адреса — потрібно оновити"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            Text(
                                text = "Адресу ще не додано",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Text(
                                text = "Додайте адресу для персонального графіка світла",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            showAddressPicker = true
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(
                            horizontal = 8.dp,
                            vertical = 10.dp
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )

                        Spacer(Modifier.width(6.dp))

                        Text(
                            text = if (hasAddress) {
                                "Змінити"
                            } else {
                                "Додати"
                            },
                            maxLines = 1
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            infoDialog =
                                "Наступним кроком підключимо автоматичну " +
                                        "перевірку збереженої адреси на сайті " +
                                        "обленерго та оновлення черги."
                        },
                        enabled = canRefreshQueue,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(
                            horizontal = 8.dp,
                            vertical = 10.dp
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )

                        Spacer(Modifier.width(6.dp))

                        Text(
                            text = "Оновити чергу",
                            maxLines = 1,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }

        SettingsSectionTitle("СИСТЕМА")

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            Column {
                SettingsMenuItem(
                    icon = Icons.Default.Palette,
                    title = "Оформлення",
                    subtitle = themeName,
                    onClick = {
                        showThemeDialog = true
                    }
                )

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                SettingsMenuItem(
                    icon = Icons.Default.Notifications,
                    title = "Сповіщення",
                    subtitle = "Зміни черги та важливі повідомлення",
                    onClick = {
                        showNotificationSettings = true
                    }
                )

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                SettingsMenuItem(
                    icon = Icons.Default.Sync,
                    title = "Оновлення даних",
                    subtitle = "Перевірка та час останнього оновлення",
                    onClick = {
                        infoDialog =
                            "Тут будуть налаштування оновлення " +
                                    "даних КропLive."
                    }
                )
            }
        }

        SettingsSectionTitle("ПРО ЗАСТОСУНОК")

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            SettingsMenuItem(
                icon = Icons.Default.Info,
                title = "КропLive",
                subtitle = "Версія, джерела даних та інформація",
                onClick = {
                    infoDialog =
                        "КропLive — інформаційний застосунок " +
                                "для Кіровоградської області.\n\n" +
                                "Тут згодом з'являться версія застосунку, " +
                                "джерела даних та інша інформація."
                }
            )
        }

        Spacer(Modifier.height(12.dp))
    }

    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Palette,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = {
                Text("Тема оформлення")
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Оберіть вигляд застосунку",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(4.dp))

                    ThemeDialogOption(
                        icon = Icons.Default.Smartphone,
                        title = "Системна",
                        subtitle = "Як у налаштуваннях телефону",
                        selected = themeMode == ThemeMode.SYSTEM,
                        onClick = {
                            onThemeChange(ThemeMode.SYSTEM)
                            showThemeDialog = false
                        }
                    )

                    ThemeDialogOption(
                        icon = Icons.Default.WbSunny,
                        title = "Світла",
                        subtitle = "Світле оформлення",
                        selected = themeMode == ThemeMode.LIGHT,
                        onClick = {
                            onThemeChange(ThemeMode.LIGHT)
                            showThemeDialog = false
                        }
                    )

                    ThemeDialogOption(
                        icon = Icons.Default.DarkMode,
                        title = "Темна",
                        subtitle = "Темне оформлення",
                        selected = themeMode == ThemeMode.DARK,
                        onClick = {
                            onThemeChange(ThemeMode.DARK)
                            showThemeDialog = false
                        }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showThemeDialog = false }) {
                    Text("Готово")
                }
            }
        )
    }

    if (infoDialog != null) {
        AlertDialog(
            onDismissRequest = {
                infoDialog = null
            },
            title = {
                Text("КропLive")
            },
            text = {
                Text(infoDialog.orEmpty())
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        infoDialog = null
                    }
                ) {
                    Text("Зрозуміло")
                }
            }
        )
    }
}

@Composable
private fun SettingsSectionTitle(
    title: String
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(
            start = 4.dp,
            top = 4.dp,
            bottom = 2.dp
        )
    )
}

@Composable
private fun SettingsMenuItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = 16.dp,
                vertical = 17.dp
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(23.dp)
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )

                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Composable
private fun ThemeDialogOption(
    icon: ImageVector,
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val background = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }

    Surface(
        onClick = onClick,
        color = background,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(24.dp)
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            RadioButton(
                selected = selected,
                onClick = null
            )
        }
    }
}
@Composable
private fun NotificationSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val sections = listOf(
        Triple("Світло", Icons.Default.Bolt, listOf(
            "power_off" to "За 15 хвилин до відключення",
            "power_on" to "За 15 хвилин до відновлення")),
        Triple("Вода", Icons.Default.WaterDrop, listOf(
            "water" to "Повідомлення для моєї адреси")),
        Triple("Відходи", Icons.Default.DeleteOutline, listOf(
            "bulky" to "Великогабаритні — за день",
            "construction" to "Будівельні — за день")),
        Triple("Повітряна тривога", Icons.Default.WarningAmber, listOf(
            "air" to "Оповіщення для мого району"))
    )
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    var state by remember { mutableStateOf(sections.flatMap { it.third }.associate { it.first to NotificationPreferences.get(context, it.first) }) }
    BackHandler { onBack() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Назад") }
            Text("Сповіщення", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        HorizontalDivider()
        Column(Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                shape = RoundedCornerShape(18.dp)
            ) {
                Text("Оновлення у фоні залежить від Android. Увімкнені перемикачі зберігають ваші побажання, але системні сповіщення для всіх категорій ще не гарантовані.",
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            sections.forEach { (title, icon, entries) ->
                Card(shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier
                        .fillMaxWidth()
                        .padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        entries.forEachIndexed { index, (key, label) ->
                            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Row(Modifier
                                .fillMaxWidth()
                                .padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                Switch(checked = state[key] == true, onCheckedChange = {
                                    NotificationPreferences.set(context, key, it)
                                    state = state + (key to it)
                                    if (it && Build.VERSION.SDK_INT >= 33) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                })
                            }
                        }
                    }
                }
            }

            HorizontalDivider()

            Text(
                text = "ПЕРЕВІРКА СПОВІЩЕНЬ",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            var testResult by remember { mutableStateOf<String?>(null) }

            Text(
                text = "Тестові повідомлення не змінюють реальний статус NEPTUN.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            listOf(
                Triple("🟡 Тест жовтого рівня", "yellow", Icons.Default.WarningAmber),
                Triple("🔴 Тест червоного рівня", "red", Icons.Default.NotificationsActive),
                Triple("🟢 Тест зеленого рівня", "green", Icons.Default.CheckCircle)
            ).forEach { (label, level, icon) ->
                OutlinedButton(
                    onClick = {
                        testResult = sendAirNotificationTest(context, level)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(icon, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(label)
                }
            }

            testResult?.let { result ->
                Text(
                    text = result,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Локальна перевірка системних сповіщень. Стан NEPTUN не змінюється. */
private fun sendAirNotificationTest(context: Context, level: String): String {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        return "Надайте дозвіл на сповіщення в налаштуваннях Android."
    }
    val notifications = NotificationManagerCompat.from(context)
    if (!notifications.areNotificationsEnabled()) {
        return "Системні сповіщення вимкнені. Увімкніть їх у налаштуваннях Android."
    }

    val isGreen = level == "green"
    val channelId = if (isGreen) "air_test_green_heads_up_v2" else "air_test_${level}_v1"
    val (title, body) = when (level) {
        "red" -> "ТЕСТ · Червоний рівень NEPTUN" to
                "Тестове попередження про високий рівень небезпеки. Це не реальна тривога."
        "yellow" -> "ТЕСТ · Жовтий рівень NEPTUN" to
                "Тестове повідомлення про повітряну загрозу. Це не реальна загроза."
        else -> "ТЕСТ · Зелений рівень NEPTUN" to
                "Тестове повідомлення: активних загроз не виявлено. Це не офіційний відбій."
    }
    val vibration = when (level) {
        "red" -> longArrayOf(0L, 450L, 180L, 450L, 180L, 650L)
        "yellow" -> longArrayOf(0L, 250L, 150L, 250L)
        else -> longArrayOf(0L, 160L)
    }
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val channel = NotificationChannel(
        channelId,
        "Тест NEPTUN · " + when (level) {
            "red" -> "червоний"
            "yellow" -> "жовтий"
            else -> "зелений"
        },
        NotificationManager.IMPORTANCE_HIGH
    ).apply {
        description = "Лише тестові повідомлення КропіLive"
        enableVibration(true)
        vibrationPattern = vibration
    }
    manager.createNotificationChannel(channel)

    val openApp = context.packageManager.getLaunchIntentForPackage(context.packageName)
    val pendingIntent = openApp?.let {
        PendingIntent.getActivity(
            context,
            4100,
            it.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
    val notification = NotificationCompat.Builder(context, channelId)
        .setSmallIcon(android.R.drawable.ic_dialog_alert)
        .setContentTitle(title)
        .setContentText(body)
        .setStyle(NotificationCompat.BigTextStyle().bigText(body))
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setCategory(NotificationCompat.CATEGORY_STATUS)
        .setVibrate(vibration)
        .setAutoCancel(true)
        .apply { if (pendingIntent != null) setContentIntent(pendingIntent) }
        .build()
    return try {
        @Suppress("MissingPermission")
        notifications.notify(
            when (level) { "red" -> 4101; "yellow" -> 4102; else -> 4103 },
            notification
        )
        "Тест надіслано. Якщо немає спливного вікна, перевірте налаштування каналу в Android."
    } catch (_: SecurityException) {
        "Android заборонив показ сповіщення. Перевірте дозволи."
    }
}