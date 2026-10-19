package ua.kropilive.app.ui.schedules

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ua.kropilive.app.data.repository.KiroeTariffsRepository
import ua.kropilive.app.data.repository.KiroeTariffPublication
import ua.kropilive.app.data.repository.HouseholdElectricityTariffsRepository
import ua.kropilive.app.data.repository.HouseholdElectricityTariffs

@Composable
fun ElectricityTariffsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var data by remember { mutableStateOf<KiroeTariffPublication?>(null) }
    var household by remember { mutableStateOf(HouseholdElectricityTariffsRepository.lastVerified) }
    var householdError by remember { mutableStateOf(false) }
    var showBusiness by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        loading = true
        error = null
        try {
            data = KiroeTariffsRepository.latest()
        } catch (e: Exception) {
            error = "Не вдалося отримати актуальні тарифи. Перевірте інтернет або відкрийте офіційний сайт."
        } finally {
            loading = false
        }
    }
    LaunchedEffect(Unit) {
        try {
            household = HouseholdElectricityTariffsRepository.load()
            householdError = false
        } catch (_: Exception) {
            householdError = true
        }
    }
    LaunchedEffect(showBusiness) { if (showBusiness && data == null) reload() }

    fun openSite() {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(KiroeTariffsRepository.URL_SOURCE)))
        } catch (_: Exception) { }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
            }
            Spacer(Modifier.width(4.dp))
            Text("Тарифи на світло", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        HorizontalDivider()
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Для населення", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                shape = RoundedCornerShape(22.dp)
            ) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Звичайний тариф", style = MaterialTheme.typography.titleMedium)
                    Text("${String.format(java.util.Locale.US, "%.2f", household.regular).replace('.', ',')} грн/кВт·год",
                        style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface)
                    Text("З ПДВ · для побутових споживачів",
                        color = MaterialTheme.colorScheme.onSurface)
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("Електроопалення", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Жовтень — квітень, за умови офіційно оформленого електроопалення")
                    HorizontalDivider()
                    TariffDetailRow("До 2000 кВт·год/міс", "2,64 грн")
                    TariffDetailRow("Понад 2000 кВт·год", "4,32 грн")
                    TariffDetailRow("Травень — вересень", "4,32 грн")
                    Text("Пільга залежить від підтвердженого статусу житла. Перевіряйте умови в КОЕК.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Багатозонні лічильники", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Двозонний облік", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    TariffDetailRow("День · 07:00–23:00", "4,32 грн")
                    TariffDetailRow("Ніч · 23:00–07:00", "2,16 грн")
                    HorizontalDivider()
                    Text("Тризонний облік", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    TariffDetailRow("Пік · 08–11, 20–22", "6,48 грн")
                    TariffDetailRow("Напівпік · інші денні години", "4,32 грн")
                    TariffDetailRow("Ніч · 23:00–07:00", "1,728 грн")
                    Text("Зональні ціни наведені для базового тарифу 4,32 грн/кВт·год.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(if (household.sourceChecked && !householdError)
                "Тарифи звірено з сайтом КОЕК під час відкриття."
                else "Показано останні перевірені значення станом на 17.10.2026. Актуальність зараз не підтверджена.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                try { context.startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse(HouseholdElectricityTariffsRepository.SOURCE))) } catch (_: Exception) {}
            }, modifier = Modifier.fillMaxWidth()) { Text("Офіційні тарифи для населення — КОЕК") }
            HorizontalDivider()
            OutlinedButton(onClick = { showBusiness = !showBusiness }, modifier = Modifier.fillMaxWidth()) {
                Text(if (showBusiness) "Приховати тарифи на розподіл для бізнесу"
                     else "Показати тарифи на розподіл (непобутові)")
            }
            if (showBusiness) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                shape = RoundedCornerShape(22.dp)
            ) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Default.ReceiptLong, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text("Тарифи Кіровоградобленерго", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    Text("Остання опублікована зміна тарифів на розподіл електроенергії",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (error != null) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(error!!, modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
            data?.let { tariff ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Опубліковано: ${tariff.published}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Період дії: ${tariff.effective}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(tariff.regulation, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                TariffElectricityCard("1 клас напруги", tariff.class1)
                TariffElectricityCard("2 клас напруги", tariff.class2)
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Важливо", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Це тарифи оператора системи розподілу, а не побутова ціна за 1 кВт·год у квитанції. Суми вказані без ПДВ.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("1 клас — 27,5 кВ та вище. 2 клас — менше 27,5 кВ.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Button(onClick = ::openSite, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Офіційні тарифи на сайті")
            }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun TariffElectricityCard(label: String, amount: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("$amount грн/МВт·год", style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text("Без ПДВ", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TariffDetailRow(label: String, amount: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(amount, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    }
}
