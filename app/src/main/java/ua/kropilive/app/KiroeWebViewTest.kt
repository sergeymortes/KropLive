
package ua.kropilive.app

import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONArray
import org.json.JSONObject

data class KiroeCommunity(
    val id: String,
    val name: String
)

data class KiroeCity(
    val id: String,
    val name: String
)

data class KiroeStreet(
    val id: String,
    val name: String
)

data class KiroeHouse(
    val id: String,
    val name: String,
    val queue: String
)

private fun decodeKiroeResult(raw: String): String {
    return JSONArray("[$raw]").getString(0)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KiroeWebViewTest(
    onBack: () -> Unit
) {
    var webView by remember {
        mutableStateOf<WebView?>(null)
    }

    var status by remember {
        mutableStateOf("Завантаження сайту...")
    }

    var pageReady by remember {
        mutableStateOf(false)
    }

    var communities by remember {
        mutableStateOf<List<KiroeCommunity>>(emptyList())
    }

    var cities by remember {
        mutableStateOf<List<KiroeCity>>(emptyList())
    }

    var streets by remember {
        mutableStateOf<List<KiroeStreet>>(emptyList())
    }

    var houses by remember {
        mutableStateOf<List<KiroeHouse>>(emptyList())
    }

    var selectedCommunity by remember {
        mutableStateOf<KiroeCommunity?>(null)
    }

    var selectedCity by remember {
        mutableStateOf<KiroeCity?>(null)
    }

    var streetText by remember {
        mutableStateOf("")
    }

    var houseText by remember {
        mutableStateOf("")
    }

    var selectedStreet by remember {
        mutableStateOf<KiroeStreet?>(null)
    }

    var selectedHouse by remember {
        mutableStateOf<KiroeHouse?>(null)
    }

    var queue by remember {
        mutableStateOf("")
    }

    var communityExpanded by remember {
        mutableStateOf(false)
    }

    var cityExpanded by remember {
        mutableStateOf(false)
    }

    var showStreetSuggestions by remember {
        mutableStateOf(false)
    }

    var showHouseSuggestions by remember {
        mutableStateOf(false)
    }

    var loadingCities by remember {
        mutableStateOf(false)
    }

    var loadingStreets by remember {
        mutableStateOf(false)
    }

    var loadingHouses by remember {
        mutableStateOf(false)
    }

    var requestNumber by remember {
        mutableIntStateOf(0)
    }

    BackHandler {
        onBack()
    }

    fun evaluate(
        script: String,
        callback: (String) -> Unit
    ) {
        val view = webView ?: return

        view.evaluateJavascript(script) { raw ->
            val result = try {
                decodeKiroeResult(raw)
            } catch (e: Exception) {
                "ERROR: $raw"
            }

            callback(result)
        }
    }

    fun readCommunities() {
        evaluate(
            """
            (function() {
                const select =
                    document.getElementById('fn_office');

                if (!select) return "[]";

                return JSON.stringify(
                    Array.from(select.options)
                        .filter(o => o.value && o.text.trim())
                        .map(o => ({
                            id: o.value,
                            name: o.text.trim()
                        }))
                );
            })();
            """.trimIndent()
        ) { result ->
            try {
                val array = JSONArray(result)

                communities = (0 until array.length()).map { i ->
                    val item = array.getJSONObject(i)

                    KiroeCommunity(
                        item.getString("id"),
                        item.getString("name")
                    )
                }

                status = "Громад: ${communities.size}"

            } catch (e: Exception) {
                status = "Помилка громад: ${e.message}"
            }
        }
    }

    fun readCities(request: Int, attempt: Int) {
        if (request != requestNumber) return

        evaluate(
            """
            (function() {
                const select =
                    document.getElementById('fn_city');

                if (!select) {
                    return JSON.stringify({
                        ready: false,
                        items: []
                    });
                }

                const items = Array.from(select.options)
                    .filter(o => o.value && o.text.trim())
                    .map(o => ({
                        id: o.value,
                        name: o.text.trim()
                    }));

                return JSON.stringify({
                    ready: !select.disabled && items.length > 0,
                    items: items
                });
            })();
            """.trimIndent()
        ) { result ->
            if (request != requestNumber) return@evaluate

            try {
                val json = JSONObject(result)

                if (json.optBoolean("ready")) {
                    val array = json.getJSONArray("items")

                    cities = (0 until array.length()).map { i ->
                        val item = array.getJSONObject(i)

                        KiroeCity(
                            item.getString("id"),
                            item.getString("name")
                        )
                    }

                    loadingCities = false
                    status = "Населених пунктів: ${cities.size}"

                } else if (attempt < 20) {
                    webView?.postDelayed(
                        { readCities(request, attempt + 1) },
                        500L
                    )

                } else {
                    loadingCities = false
                    status = "Не вдалося завантажити населені пункти"
                }

            } catch (e: Exception) {
                loadingCities = false
                status = "Помилка міст: ${e.message}"
            }
        }
    }

    fun readStreets(request: Int, attempt: Int) {
        if (request != requestNumber) return

        evaluate(
            """
            (function() {
                const ac = window.jQuery
                    ? jQuery('#fn_street').data('autocomplete')
                    : null;

                const lookup = ac?.options?.lookup;

                if (!Array.isArray(lookup)) {
                    return JSON.stringify({
                        ready: false,
                        items: []
                    });
                }

                const items = lookup.map(item => ({
                    id: String(item.data?.StreetId || ""),
                    name: String(item.value || "")
                })).filter(item => item.id && item.name);

                return JSON.stringify({
                    ready: items.length > 0,
                    items: items
                });
            })();
            """.trimIndent()
        ) { result ->
            if (request != requestNumber) return@evaluate

            try {
                val json = JSONObject(result)

                if (json.optBoolean("ready")) {
                    val array = json.getJSONArray("items")

                    streets = (0 until array.length()).map { i ->
                        val item = array.getJSONObject(i)

                        KiroeStreet(
                            item.getString("id"),
                            item.getString("name")
                        )
                    }.distinctBy { it.id }

                    loadingStreets = false
                    status = "Вулиць: ${streets.size}"

                } else if (attempt < 20) {
                    webView?.postDelayed(
                        { readStreets(request, attempt + 1) },
                        500L
                    )

                } else {
                    loadingStreets = false
                    status = "Вулиці не завантажилися"
                }

            } catch (e: Exception) {
                loadingStreets = false
                status = "Помилка вулиць: ${e.message}"
            }
        }
    }

    fun readHouses(request: Int, attempt: Int) {
        if (request != requestNumber) return

        evaluate(
            """
            (function() {
                const ac = window.jQuery
                    ? jQuery('#fn_house').data('autocomplete')
                    : null;

                const lookup = ac?.options?.lookup;

                if (!Array.isArray(lookup)) {
                    return JSON.stringify({
                        ready: false,
                        items: []
                    });
                }

                const items = lookup.map(item => ({
                    id: String(item.data?.HouseId || ""),
                    name: String(item.value || ""),
                    queue: String(item.data?.chergGpv || "")
                })).filter(item => item.id && item.name);

                return JSON.stringify({
                    ready: items.length > 0,
                    items: items
                });
            })();
            """.trimIndent()
        ) { result ->
            if (request != requestNumber) return@evaluate

            try {
                val json = JSONObject(result)

                if (json.optBoolean("ready")) {
                    val array = json.getJSONArray("items")

                    houses = (0 until array.length()).map { i ->
                        val item = array.getJSONObject(i)

                        KiroeHouse(
                            id = item.getString("id"),
                            name = item.getString("name"),
                            queue = item.getString("queue")
                        )
                    }.distinctBy { it.id }

                    loadingHouses = false
                    status = "Будинків: ${houses.size}"

                } else if (attempt < 20) {
                    webView?.postDelayed(
                        { readHouses(request, attempt + 1) },
                        500L
                    )

                } else {
                    loadingHouses = false
                    status = "Будинки не завантажилися"
                }

            } catch (e: Exception) {
                loadingHouses = false
                status = "Помилка будинків: ${e.message}"
            }
        }
    }

    fun selectCommunity(item: KiroeCommunity) {
        selectedCommunity = item
        selectedCity = null
        selectedStreet = null
        selectedHouse = null

        cities = emptyList()
        streets = emptyList()
        houses = emptyList()

        streetText = ""
        houseText = ""
        queue = ""

        loadingCities = true
        loadingStreets = false
        loadingHouses = false

        requestNumber++
        val request = requestNumber

        val id = JSONObject.quote(item.id)

        status = "Завантаження населених пунктів..."

        evaluate(
            """
            (function() {
                try {
                    const select = document.getElementById('fn_office');
                    const jq = window.jQuery;
                    const id = $id;

                    const option = Array.from(select.options)
                        .find(o => o.value === id);

                    if (!option || !jq) return "ERROR: office";

                    jq(select).val(id).trigger('change');

                    jq(select).trigger({
                        type: 'select2:select',
                        params: {
                            data: {
                                id: id,
                                text: option.text.trim()
                            }
                        }
                    });

                    return "SUCCESS";

                } catch (e) {
                    return "ERROR: " + e.message;
                }
            })();
            """.trimIndent()
        ) { result ->
            if (request != requestNumber) return@evaluate

            if (result == "SUCCESS") {
                readCities(request, 0)
            } else {
                loadingCities = false
                status = result
            }
        }
    }

    fun selectCity(item: KiroeCity) {
        selectedCity = item
        selectedStreet = null
        selectedHouse = null

        streets = emptyList()
        houses = emptyList()

        streetText = ""
        houseText = ""
        queue = ""

        loadingStreets = true
        loadingHouses = false

        requestNumber++
        val request = requestNumber

        val id = JSONObject.quote(item.id)

        status = "Завантаження вулиць..."

        evaluate(
            """
            (function() {
                try {
                    const select = document.getElementById('fn_city');
                    const jq = window.jQuery;
                    const id = $id;

                    const option = Array.from(select.options)
                        .find(o => o.value === id);

                    if (!option || !jq) return "ERROR: city";

                    jq(select).val(id).trigger('change');

                    jq(select).trigger({
                        type: 'select2:select',
                        params: {
                            data: {
                                id: id,
                                text: option.text.trim()
                            }
                        }
                    });

                    return "SUCCESS";

                } catch (e) {
                    return "ERROR: " + e.message;
                }
            })();
            """.trimIndent()
        ) { result ->
            if (request != requestNumber) return@evaluate

            if (result == "SUCCESS") {
                readStreets(request, 0)
            } else {
                loadingStreets = false
                status = result
            }
        }
    }

    fun selectStreet(item: KiroeStreet) {
        selectedStreet = item
        selectedHouse = null

        streetText = item.name
        houseText = ""
        houses = emptyList()
        queue = ""

        showStreetSuggestions = false
        loadingHouses = true

        requestNumber++
        val request = requestNumber

        val id = JSONObject.quote(item.id)

        status = "Завантаження будинків..."

        evaluate(
            """
            (function() {
                try {
                    const ac = jQuery('#fn_street')
                        .data('autocomplete');

                    if (!ac) return "ERROR: street autocomplete";

                    const id = $id;

                    const suggestion = ac.options.lookup.find(
                        item => String(item.data?.StreetId) === id
                    );

                    if (!suggestion) return "ERROR: street not found";

                    jQuery('#fn_street').val(suggestion.value);

                    ac.selection = suggestion;
                    ac.options.onSelect(suggestion);

                    return "SUCCESS";

                } catch (e) {
                    return "ERROR: " + e.message;
                }
            })();
            """.trimIndent()
        ) { result ->
            if (request != requestNumber) return@evaluate

            if (result == "SUCCESS") {
                readHouses(request, 0)
            } else {
                loadingHouses = false
                status = result
            }
        }
    }

    fun selectHouse(item: KiroeHouse) {
        selectedHouse = item
        houseText = item.name
        queue = item.queue

        showHouseSuggestions = false

        requestNumber++
        val request = requestNumber

        val id = JSONObject.quote(item.id)

        status = "Завантаження графіка..."

        evaluate(
            """
            (function() {
                try {
                    const ac = jQuery('#fn_house')
                        .data('autocomplete');

                    if (!ac) return "ERROR: house autocomplete";

                    const id = $id;

                    const suggestion = ac.options.lookup.find(
                        item => String(item.data?.HouseId) === id
                    );

                    if (!suggestion) return "ERROR: house not found";

                    jQuery('#fn_house').val(suggestion.value);

                    ac.selection = suggestion;
                    ac.options.onSelect(suggestion);

                    return "SUCCESS";

                } catch (e) {
                    return "ERROR: " + e.message;
                }
            })();
            """.trimIndent()
        ) { result ->
            if (request != requestNumber) return@evaluate

            status = if (result == "SUCCESS") {
                "Будинок обрано. Черга: ${item.queue}"
            } else {
                result
            }
        }
    }

    val filteredStreets = remember(streetText, streets) {
        if (streetText.isBlank()) {
            emptyList()
        } else {
            streets.filter {
                it.name.contains(streetText, ignoreCase = true)
            }.take(12)
        }
    }

    val filteredHouses = remember(houseText, houses) {
        if (houseText.isBlank()) {
            emptyList()
        } else {
            houses.filter {
                it.name.startsWith(houseText, ignoreCase = true)
            }.take(12)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        Text(
            text = "Перевірка адреси",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(12.dp)
        )

        Text(
            text = status,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 12.dp)
        )

        Spacer(Modifier.height(8.dp))

        ExposedDropdownMenuBox(
            expanded = communityExpanded,
            onExpandedChange = {
                communityExpanded = it
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
        ) {
            OutlinedTextField(
                value = selectedCommunity?.name ?: "",
                onValueChange = {},
                readOnly = true,
                label = { Text("Громада") },
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(
                        expanded = communityExpanded
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor()
            )

            ExposedDropdownMenu(
                expanded = communityExpanded,
                onDismissRequest = {
                    communityExpanded = false
                }
            ) {
                communities.forEach { item ->
                    DropdownMenuItem(
                        text = { Text(item.name) },
                        onClick = {
                            communityExpanded = false
                            selectCommunity(item)
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        ExposedDropdownMenuBox(
            expanded = cityExpanded,
            onExpandedChange = {
                cityExpanded = it
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
        ) {
            OutlinedTextField(
                value = selectedCity?.name ?: "",
                onValueChange = {},
                readOnly = true,
                enabled = cities.isNotEmpty(),
                label = { Text("Населений пункт") },
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(
                        expanded = cityExpanded
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor()
            )

            ExposedDropdownMenu(
                expanded = cityExpanded,
                onDismissRequest = {
                    cityExpanded = false
                }
            ) {
                cities.forEach { item ->
                    DropdownMenuItem(
                        text = { Text(item.name) },
                        onClick = {
                            cityExpanded = false
                            selectCity(item)
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = streetText,
            onValueChange = {
                streetText = it
                selectedStreet = null
                selectedHouse = null
                houseText = ""
                houses = emptyList()
                queue = ""
                showStreetSuggestions = true
            },
            enabled = streets.isNotEmpty(),
            label = { Text("Вулиця") },
            placeholder = { Text("Почніть вводити назву") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
        )

        if (showStreetSuggestions && filteredStreets.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 150.dp)
                    .padding(horizontal = 12.dp)
            ) {
                items(filteredStreets) { item ->
                    Text(
                        text = item.name,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectStreet(item)
                            }
                            .padding(10.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = houseText,
            onValueChange = {
                houseText = it
                selectedHouse = null
                queue = ""
                showHouseSuggestions = true
            },
            enabled = houses.isNotEmpty(),
            label = { Text("Будинок") },
            placeholder = { Text("Введіть номер будинку") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
        )

        if (showHouseSuggestions && filteredHouses.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 150.dp)
                    .padding(horizontal = 12.dp)
            ) {
                items(filteredHouses) { item ->
                    Text(
                        text = item.name,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectHouse(item)
                            }
                            .padding(10.dp)
                    )
                }
            }
        }

        if (selectedHouse != null) {
            Spacer(Modifier.height(12.dp))

            Text(
                text = "Ваша черга: ${queue.ifBlank { "Не визначено" }}",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
        }

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = { readCommunities() },
            enabled = pageReady,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
        ) {
            Text("Зчитати громади")
        }

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 8.dp)
        )

        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true

                    webChromeClient = WebChromeClient()

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(
                            view: WebView?,
                            url: String?
                        ) {
                            super.onPageFinished(view, url)

                            pageReady = true
                            status = "Сайт завантажено"
                        }
                    }

                    webView = this

                    loadUrl(
                        "https://kiroe.com.ua/electricity-blackout"
                    )
                }
            }
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            requestNumber++
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }
    }
}
