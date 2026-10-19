package ua.kropilive.app.ui.schedules

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private const val TARIFFS_URL = "https://www.ecostyle.ua/taryfy"

@Composable
fun EcostyleTariffsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    fun openSource() {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TARIFFS_URL))) }
        catch (_: Exception) { }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Назад") }
            Spacer(Modifier.width(4.dp))
            Text("Тарифи Екостайл", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        HorizontalDivider()
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Default.ReceiptLong, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text("Вартість послуг", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                    Text("Тарифи, опубліковані Екостайл, станом на 01.02.2026", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Суми вказані у гривнях, з ПДВ. Перед оплатою перевіряйте актуальні дані на офіційному сайті.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            TariffCard("Приватний сектор", "91,39 грн", "за одну особу на місяць", listOf(
                "Змішані побутові відходи" to "75,70 грн",
                "Великогабаритні відходи" to "13,17 грн",
                "Ремонтні відходи" to "2,52 грн"
            ))
            TariffCard("Багатоповерхівки", "87,23 грн", "за одну особу на місяць", listOf(
                "Змішані побутові відходи" to "71,54 грн",
                "Великогабаритні відходи" to "13,17 грн",
                "Ремонтні відходи" to "2,52 грн"
            ))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Абонентське обслуговування", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("13,26 грн/місяць", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text("За один особовий рахунок, додатково до плати за послугу, не за кожну особу.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Бюджетні установи та інші споживачі", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("356,23 грн/м³", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("За фактичний обсяг відходів", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider()
                    TariffLine("Абонентське обслуговування", "22,35 грн/міс.")
                }
            }
            Text("Оплата за попередній місяць — з 1 по 20 число.", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = ::openSource, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Перевірити тарифи на сайті Екостайл")
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun TariffCard(title: String, amount: String, units: String, items: List<Pair<String, String>>) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(amount, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Text(units, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
            items.forEach { (name, value) -> TariffLine(name, value) }
        }
    }
}

@Composable
private fun TariffLine(name: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Text(name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
    }
}
