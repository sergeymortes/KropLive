package ua.kropilive.app

import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import ua.kropilive.app.data.repository.AddressRepository
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.window.PopupProperties

private data class AddressOption(
    val id: String,
    val name: String
)

private data class AddressHouse(
    val id: String,
    val name: String,
    val queue: String
)

private fun parseWebResult(raw: String): String {
    return try {
        JSONArray("[$raw]").getString(0)
    } catch (e: Exception) {
        "ERROR: $raw"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KiroeAddressPicker(
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current.applicationContext

    val repository = remember(context) {
        AddressRepository(context)
    }

    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    var webView by remember {
        mutableStateOf<WebView?>(null)
    }

    var status by remember {
        mutableStateOf("Підключення до обленерго...")
    }

    var communities by remember {
        mutableStateOf<List<AddressOption>>(emptyList())
    }

    var cities by remember {
        mutableStateOf<List<AddressOption>>(emptyList())
    }

    var streets by remember {
        mutableStateOf<List<AddressOption>>(emptyList())
    }

    var houses by remember {
        mutableStateOf<List<AddressHouse>>(emptyList())
    }

    var selectedCommunity by remember {
        mutableStateOf<AddressOption?>(null)
    }

    var selectedCity by remember {
        mutableStateOf<AddressOption?>(null)
    }

    var selectedStreet by remember {
        mutableStateOf<AddressOption?>(null)
    }

    var selectedHouse by remember {
        mutableStateOf<AddressHouse?>(null)
    }

    var streetText by remember {
        mutableStateOf("")
    }

    var houseText by remember {
        mutableStateOf("")
    }

    var communityExpanded by remember {
        mutableStateOf(false)
    }

    var cityExpanded by remember {
        mutableStateOf(false)
    }

    var streetExpanded by remember {
        mutableStateOf(false)
    }

    var houseExpanded by remember {
        mutableStateOf(false)
    }

    var loading by remember {
        mutableStateOf(false)
    }

    var saving by remember {
        mutableStateOf(false)
    }

    var houseVerified by remember {
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
        val view = webView

        if (view == null) {
            callback("ERROR: WebView not ready")
            return
        }

        view.evaluateJavascript(script) { raw ->
            callback(parseWebResult(raw))
        }
    }

    fun readCommunities(attempt: Int = 0) {
        evaluate(
            """
            (function() {
                try {
                    const select = document.getElementById('fn_office');
                    if (!select) return "[]";

                    return JSON.stringify(
                        Array.from(select.options)
                            .filter(o => o.value && o.text.trim())
                            .map(o => ({
                                id: String(o.value),
                                name: o.text.trim()
                            }))
                    );
                } catch (e) {
                    return "[]";
                }
            })();
            """.trimIndent()
        ) { result ->
            try {
                val array = JSONArray(result)

                communities = (0 until array.length()).map { i ->
                    val item = array.getJSONObject(i)
                    AddressOption(
                        id = item.getString("id"),
                        name = item.getString("name")
                    )
                }

                if (communities.isNotEmpty()) {
                    status = "Оберіть громаду"
                } else if (attempt < 20) {
                    status = "Завантаження громад..."
                    webView?.postDelayed(
                        { readCommunities(attempt + 1) },
                        500L
                    )
                } else {
                    status = "Не вдалося завантажити громади. Перевірте інтернет-з’єднання."
                }

            } catch (e: Exception) {
                status = "Помилка завантаження громад"
            }
        }
    }

    fun readCities(request: Int, attempt: Int) {
        if (request != requestNumber) return

        evaluate(
            """
            (function() {
                try {
                    const select = document.getElementById('fn_city');

                    if (!select) {
                        return JSON.stringify({
                            ready: false,
                            items: []
                        });
                    }

                    const items = Array.from(select.options)
                        .filter(o => o.value && o.text.trim())
                        .map(o => ({
                            id: String(o.value),
                            name: o.text.trim()
                        }));

                    return JSON.stringify({
                        ready: !select.disabled && items.length > 0,
                        items: items
                    });
                } catch (e) {
                    return JSON.stringify({
                        ready: false,
                        items: []
                    });
                }
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
                        AddressOption(
                            item.getString("id"),
                            item.getString("name")
                        )
                    }

                    loading = false
                    status = "Оберіть населений пункт"

                } else if (attempt < 20) {
                    webView?.postDelayed(
                        { readCities(request, attempt + 1) },
                        500L
                    )
                } else {
                    loading = false
                    status = "Не вдалося завантажити населені пункти"
                }
            } catch (e: Exception) {
                loading = false
                status = "Помилка населених пунктів: ${e.message}"
            }
        }
    }

    fun readStreets(request: Int, attempt: Int) {
        if (request != requestNumber) return

        evaluate(
            """
            (function() {
                try {
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

                    const items = lookup
                        .map(item => ({
                            id: String(item.data?.StreetId || ""),
                            name: String(item.value || "")
                        }))
                        .filter(item => item.id && item.name);

                    return JSON.stringify({
                        ready: items.length > 0,
                        items: items
                    });
                } catch (e) {
                    return JSON.stringify({
                        ready: false,
                        items: []
                    });
                }
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
                        AddressOption(
                            item.getString("id"),
                            item.getString("name")
                        )
                    }.distinctBy { it.id }

                    loading = false
                    status = "Вулиць знайдено: ${streets.size}"

                } else if (attempt < 20) {
                    webView?.postDelayed(
                        { readStreets(request, attempt + 1) },
                        500L
                    )
                } else {
                    loading = false
                    status = "Не вдалося завантажити вулиці"
                }
            } catch (e: Exception) {
                loading = false
                status = "Помилка вулиць: ${e.message}"
            }
        }
    }

    fun readHouses(request: Int, attempt: Int) {
        if (request != requestNumber) return

        evaluate(
            """
            (function() {
                try {
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

                    const items = lookup
                        .map(item => ({
                            id: String(item.data?.HouseId || ""),
                            name: String(item.value || ""),
                            queue: String(item.data?.chergGpv || "")
                        }))
                        .filter(item => item.id && item.name);

                    return JSON.stringify({
                        ready: items.length > 0,
                        items: items
                    });
                } catch (e) {
                    return JSON.stringify({
                        ready: false,
                        items: []
                    });
                }
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
                        AddressHouse(
                            id = item.getString("id"),
                            name = item.getString("name"),
                            queue = item.getString("queue")
                        )
                    }.distinctBy { it.id }

                    loading = false
                    status = "Оберіть будинок"

                } else if (attempt < 20) {
                    webView?.postDelayed(
                        { readHouses(request, attempt + 1) },
                        500L
                    )
                } else {
                    loading = false
                    status = "Не вдалося завантажити будинки"
                }
            } catch (e: Exception) {
                loading = false
                status = "Помилка будинків: ${e.message}"
            }
        }
    }

    fun selectCommunity(item: AddressOption) {
        selectedCommunity = item
        selectedCity = null
        selectedStreet = null
        selectedHouse = null

        cities = emptyList()
        streets = emptyList()
        houses = emptyList()

        streetText = ""
        houseText = ""
        houseVerified = false

        communityExpanded = false
        loading = true

        requestNumber++
        val request = requestNumber

        status = "Завантаження населених пунктів..."

        val id = JSONObject.quote(item.id)

        evaluate(
            """
            (function() {
                try {
                    const select = document.getElementById('fn_office');
                    const jq = window.jQuery;
                    const id = $id;

                    if (!select || !jq) return "ERROR: office";

                    const option = Array.from(select.options)
                        .find(o => o.value === id);

                    if (!option) return "ERROR: office ID";

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
                loading = false
                status = result
            }
        }
    }

    fun selectCity(item: AddressOption) {
        selectedCity = item
        selectedStreet = null
        selectedHouse = null

        streets = emptyList()
        houses = emptyList()

        streetText = ""
        houseText = ""
        houseVerified = false

        cityExpanded = false
        loading = true

        requestNumber++
        val request = requestNumber

        status = "Завантаження вулиць..."

        val id = JSONObject.quote(item.id)

        evaluate(
            """
            (function() {
                try {
                    const select = document.getElementById('fn_city');
                    const jq = window.jQuery;
                    const id = $id;

                    if (!select || !jq) return "ERROR: city";

                    const option = Array.from(select.options)
                        .find(o => o.value === id);

                    if (!option) return "ERROR: city ID";

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
                loading = false
                status = result
            }
        }
    }

    fun selectStreet(item: AddressOption) {
        keyboardController?.hide()
        focusManager.clearFocus()
        selectedStreet = item
        selectedHouse = null

        streetText = item.name
        houseText = ""
        houses = emptyList()
        houseVerified = false

        streetExpanded = false
        loading = true

        requestNumber++
        val request = requestNumber

        status = "Завантаження будинків..."

        val id = JSONObject.quote(item.id)

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

                    if (!suggestion) return "ERROR: street ID";

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
                loading = false
                status = result
            }
        }
    }

    fun selectHouse(item: AddressHouse) {
        keyboardController?.hide()
        focusManager.clearFocus()
        selectedHouse = item
        houseText = item.name
        houseVerified = false
        houseExpanded = false
        loading = true

        requestNumber++
        val request = requestNumber

        status = "Перевірка адреси..."

        val id = JSONObject.quote(item.id)

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

                    if (!suggestion) return "ERROR: house ID";

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

            loading = false

            if (result == "SUCCESS") {
                houseVerified = true
                status = "Адресу обрано. Можна зберігати."
            } else {
                houseVerified = false
                status = result
            }
        }
    }

    val streetSuggestions = remember(streetText, streets) {
        if (streetText.isBlank()) {
            emptyList()
        } else {
            streets.filter {
                it.name.contains(streetText, ignoreCase = true)
            }.take(10)
        }
    }

    val houseSuggestions = remember(houseText, houses) {
        if (houseText.isBlank()) {
            emptyList()
        } else {
            houses.filter {
                it.name.startsWith(houseText, ignoreCase = true)
            }.take(10)
        }
    }

    // Выбранное значение подсвечиваем зелёным, незаполненное — нейтральным.
    // Цвет зелёного подбирается отдельно для светлого и тёмного фона.
    val completedGreen = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
        Color(0xFF4ADE80)
    } else {
        Color(0xFF15803D)
    }

    @Composable
    fun fieldColors(completed: Boolean): TextFieldColors {
        val accent = if (completed) completedGreen else MaterialTheme.colorScheme.outline
        val label = if (completed) completedGreen else MaterialTheme.colorScheme.onSurfaceVariant
        return OutlinedTextFieldDefaults.colors(
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            disabledTextColor = if (completed) completedGreen else MaterialTheme.colorScheme.onSurfaceVariant,
            focusedLabelColor = label,
            unfocusedLabelColor = label,
            disabledLabelColor = label,
            focusedBorderColor = accent,
            unfocusedBorderColor = accent,
            disabledBorderColor = accent,
            focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
            disabledPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    val canSave =
        selectedCommunity != null &&
                selectedCity != null &&
                selectedStreet != null &&
                selectedHouse != null &&
                houseVerified &&
                !loading &&
                !saving

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
            .imePadding()
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.Default.ArrowBack,
                        contentDescription = "Назад",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }

                Column {
                    Text(
                        text = "Моя адреса",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )

                    Text(
                        text = "Налаштування домашньої адреси",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider()

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.LocationOn,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )

                    Text(
                        text = "Оберіть вашу адресу",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }

                ExposedDropdownMenuBox(
                    expanded = communityExpanded,
                    onExpandedChange = {
                        if (communities.isNotEmpty() && !loading) {
                            communityExpanded = it
                        }
                    }
                ) {
                    OutlinedTextField(
                        colors = fieldColors(selectedCommunity != null),
                        value = selectedCommunity?.name ?: "",
                        onValueChange = {},
                        readOnly = true,
                        enabled = communities.isNotEmpty() && !loading,
                        label = { Text("Громада") },
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (selectedCommunity != null) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = "Обрано",
                                        tint = completedGreen,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                ExposedDropdownMenuDefaults.TrailingIcon(
                                    expanded = communityExpanded
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )

                    ExposedDropdownMenu(
                        expanded = communityExpanded,
                        onDismissRequest = {
                            communityExpanded = false
                        },
                        modifier = Modifier.heightIn(max = 300.dp)
                    ) {
                        communities.forEach { item ->
                            DropdownMenuItem(
                                text = { Text(item.name) },
                                onClick = {
                                    selectCommunity(item)
                                }
                            )
                        }
                    }
                }

                ExposedDropdownMenuBox(
                    expanded = cityExpanded,
                    onExpandedChange = {
                        if (cities.isNotEmpty() && !loading) {
                            cityExpanded = it
                        }
                    }
                ) {
                    OutlinedTextField(
                        colors = fieldColors(selectedCity != null),
                        value = selectedCity?.name ?: "",
                        onValueChange = {},
                        readOnly = true,
                        enabled = cities.isNotEmpty() && !loading,
                        label = { Text("Населений пункт") },
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (selectedCity != null) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = "Обрано",
                                        tint = completedGreen,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                ExposedDropdownMenuDefaults.TrailingIcon(
                                    expanded = cityExpanded
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )

                    ExposedDropdownMenu(
                        expanded = cityExpanded,
                        onDismissRequest = {
                            cityExpanded = false
                        },
                        modifier = Modifier.heightIn(max = 300.dp)
                    ) {
                        cities.forEach { item ->
                            DropdownMenuItem(
                                text = { Text(item.name) },
                                onClick = {
                                    selectCity(item)
                                }
                            )
                        }
                    }
                }

                // Підказки відкриваються у Popup поверх форми.
                // Меню саме вибирає місце над або під полем,
                // враховуючи доступний простір при відкритій клавіатурі.
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        colors = fieldColors(selectedStreet != null),
                        value = streetText,
                        onValueChange = {
                            streetText = it
                            selectedStreet = null
                            selectedHouse = null
                            houses = emptyList()
                            houseText = ""
                            houseVerified = false
                            streetExpanded = true
                        },
                        enabled = streets.isNotEmpty() && !loading,
                        label = { Text("Вулиця") },
                        trailingIcon = {
                            if (selectedStreet != null) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = "Обрано",
                                    tint = completedGreen
                                )
                            }
                        },
                        placeholder = { Text("Почніть вводити назву") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    DropdownMenu(
                        expanded = streetExpanded && streetSuggestions.isNotEmpty(),
                        onDismissRequest = { streetExpanded = false },
                        properties = PopupProperties(focusable = false),
                        modifier = Modifier
                            .fillMaxWidth(0.88f)
                            .heightIn(max = 240.dp)
                    ) {
                        streetSuggestions.forEach { item ->
                            DropdownMenuItem(
                                text = { Text(item.name) },
                                onClick = { selectStreet(item) }
                            )
                        }
                    }
                }

                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        colors = fieldColors(selectedHouse != null && houseVerified),
                        value = houseText,
                        onValueChange = {
                            houseText = it
                            selectedHouse = null
                            houseVerified = false
                            houseExpanded = true
                        },
                        enabled = houses.isNotEmpty() && !loading,
                        label = { Text("Будинок") },
                        trailingIcon = {
                            if (selectedHouse != null && houseVerified) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = "Обрано",
                                    tint = completedGreen
                                )
                            }
                        },
                        placeholder = { Text("Введіть номер будинку") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    DropdownMenu(
                        expanded = houseExpanded && houseSuggestions.isNotEmpty(),
                        onDismissRequest = { houseExpanded = false },
                        properties = PopupProperties(focusable = false),
                        modifier = Modifier
                            .fillMaxWidth(0.88f)
                            .heightIn(max = 240.dp)
                    ) {
                        houseSuggestions.forEach { item ->
                            DropdownMenuItem(
                                text = { Text(item.name) },
                                onClick = { selectHouse(item) }
                            )
                        }
                    }
                }

                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )

                        Text(
                            text = "Черга відключень визначається автоматично. " +
                                    "Переглянути її можна на головній сторінці " +
                                    "в розділі «Світло».",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (loading || communities.isEmpty()) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Button(
                onClick = {
                    val community = selectedCommunity
                    val city = selectedCity
                    val street = selectedStreet
                    val house = selectedHouse

                    if (
                        community != null &&
                        city != null &&
                        street != null &&
                        house != null &&
                        houseVerified
                    ) {
                        saving = true

                        scope.launch {
                            try {
                                repository.saveFullAddress(
                                    communityId = community.id,
                                    communityName = community.name,
                                    cityId = city.id,
                                    cityName = city.name,
                                    streetId = street.id,
                                    streetName = street.name,
                                    houseId = house.id,
                                    houseName = house.name,
                                    electricityQueue = house.queue
                                )

                                onSaved()
                            } catch (e: Exception) {
                                saving = false
                                status = "Помилка збереження: ${e.message}"
                            }
                        }
                    }
                },
                enabled = canSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null
                )

                Spacer(Modifier.width(8.dp))

                Text(
                    if (saving) {
                        "Збереження..."
                    } else {
                        "Зберегти адресу"
                    }
                )
            }
        }

        // WebView залишається прикріпленим до екрана,
        // але користувач його не бачить.
        AndroidView(
            modifier = Modifier
                .size(1.dp)
                .alpha(0f),
            factory = { viewContext ->
                WebView(viewContext).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true

                    webChromeClient = WebChromeClient()

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(
                            view: WebView?,
                            url: String?
                        ) {
                            super.onPageFinished(view, url)

                            view?.postDelayed(
                                {
                                    if (webView === view) {
                                        readCommunities()
                                    }
                                },
                                300L
                            )
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