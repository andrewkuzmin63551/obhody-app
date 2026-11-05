package ru.obhody.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.UUID

private class Store(context: Context) : SQLiteOpenHelper(context, "obhody_local.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE pending (id TEXT PRIMARY KEY, payload TEXT NOT NULL, synced INTEGER NOT NULL DEFAULT 0)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    fun save(payload: JSONObject) {
        writableDatabase.insertOrThrow("pending", null, ContentValues().apply {
            put("id", payload.getString("id")); put("payload", payload.toString()); put("synced", 0)
        })
    }
    fun counts(): Pair<Int, Int> = readableDatabase.rawQuery(
        "SELECT COUNT(*), COALESCE(SUM(CASE WHEN synced=0 THEN 1 ELSE 0 END),0) FROM pending", null
    ).use { c -> c.moveToFirst(); c.getInt(0) to c.getInt(1) }
    fun unsynced(): JSONArray {
        val arr = JSONArray()
        readableDatabase.rawQuery("SELECT payload FROM pending WHERE synced=0 LIMIT 100", null).use { c ->
            while (c.moveToNext()) arr.put(JSONObject(c.getString(0)))
        }
        return arr
    }
    fun markSynced(ids: JSONArray) {
        writableDatabase.beginTransaction()
        try {
            for (i in 0 until ids.length()) writableDatabase.update("pending", ContentValues().apply {put("synced",1)}, "id=?", arrayOf(ids.getString(i)))
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }
}

private fun request(base: String, key: String, path: String, content: JSONArray? = null): String {
    require(base.startsWith("https://") || (BuildConfig.DEBUG && base.startsWith("http://"))) { "Для рабочего сервера необходим HTTPS" }
    require(key.isNotBlank()) { "Укажите API-ключ" }
    val conn = URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection
    try {
        conn.requestMethod = if (content == null) "GET" else "POST"
        conn.connectTimeout = 10000; conn.readTimeout = 15000
        conn.setRequestProperty("X-API-Key", key)
        if (content != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(content.toString().toByteArray(Charsets.UTF_8)) }
        }
        val code = conn.responseCode
        if (code !in 200..299) throw IllegalStateException("Ошибка сервера HTTP $code")
        return conn.inputStream.bufferedReader().use { it.readText() }
    } finally {conn.disconnect()}
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = Store(this)
        val preferences = getSharedPreferences("settings", MODE_PRIVATE)
        setContent {
            var page by remember { mutableStateOf("home") }
            var routes by remember { mutableStateOf(JSONArray(preferences.getString("routes", "[]"))) }
            var routeIndex by remember { mutableIntStateOf(0) }
            var answers by remember { mutableStateOf(mapOf<String,String>()) }
            var comment by remember { mutableStateOf("") }
            var server by remember { mutableStateOf(preferences.getString("server", "http://10.0.2.2:8000") ?: "") }
            var token by remember { mutableStateOf(preferences.getString("token", "") ?: "") }
            var operator by remember { mutableStateOf(preferences.getString("operator", "") ?: "") }
            var message by remember { mutableStateOf("") }
            var busy by remember { mutableStateOf(false) }
            var counts by remember { mutableStateOf(store.counts()) }
            fun runTask(action: () -> String) {
                if (busy) return
                busy = true
                Thread {
                    val outcome = runCatching { action() }
                    runOnUiThread { busy = false; counts = store.counts(); message = outcome.fold({it}, {"Ошибка: ${it.message}"}) }
                }.start()
            }
            BackHandler(page != "home") { page = "home" }
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Обходы", style = MaterialTheme.typography.headlineMedium)
                        if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary)
                        when (page) {
                            "home" -> {
                                Text("Сохранено: ${counts.first}  •  Ожидают отправки: ${counts.second}")
                                if (routes.length() == 0) Text("Маршруты ещё не загружены. Откройте настройки и загрузите их с сервера.")
                                for (i in 0 until routes.length()) {
                                    val r = routes.getJSONObject(i)
                                    ElevatedCard(onClick = { routeIndex = i; answers = emptyMap(); comment = ""; message = ""; page = "form" },modifier=Modifier.fillMaxWidth()) {
                                        Column(Modifier.padding(16.dp)) {
                                            Text(r.optString("name"),style=MaterialTheme.typography.titleMedium)
                                            Text(r.optString("description"))
                                            Text("Точек: ${r.getJSONArray("checkpoints").length()}")
                                        }
                                    }
                                }
                                Button(onClick={page="settings"},modifier=Modifier.fillMaxWidth()) {Text("Настройки и синхронизация")}
                            }
                            "form" -> {
                                val route = routes.getJSONObject(routeIndex)
                                Text(route.getString("name"), style = MaterialTheme.typography.titleLarge)
                                val cps = route.getJSONArray("checkpoints")
                                var requiredOk = operator.isNotBlank()
                                for (c in 0 until cps.length()) {
                                    val checkpoint = cps.getJSONObject(c)
                                    HorizontalDivider()
                                    Text(checkpoint.getString("title"), style=MaterialTheme.typography.titleMedium)
                                    val qs = checkpoint.getJSONArray("questions")
                                    for (q in 0 until qs.length()) {
                                        val question = qs.getJSONObject(q)
                                        val id = question.getString("id")
                                        val kind = question.getString("kind")
                                        val value = answers[id] ?: ""
                                        Text(question.getString("title") + if(question.optBoolean("required")) " *" else "")
                                        if (kind == "status" || kind == "choice") {
                                            val options = if (kind == "status") listOf("Норма", "Дефект", "Не проверено") else {
                                                val opts = question.getJSONArray("options")
                                                (0 until opts.length()).map { opts.getString(it) }
                                            }
                                            options.forEach { choice ->
                                                Row { RadioButton(selected=value==choice,onClick={answers=answers+(id to choice)});Text(choice, modifier=Modifier.padding(top=12.dp)) }
                                            }
                                        } else {
                                            OutlinedTextField(value=value, onValueChange={answers=answers+(id to it)}, label={Text(if(kind=="number") "Число" else "Ответ")},modifier=Modifier.fillMaxWidth(),singleLine=true)
                                        }
                                        if (question.optBoolean("required") && (value.isBlank() || (kind=="number" && value.replace(',','.').toDoubleOrNull()==null))) requiredOk = false
                                    }
                                }
                                OutlinedTextField(value=comment,onValueChange={comment=it},label={Text("Комментарий")},modifier=Modifier.fillMaxWidth(),minLines=2)
                                Text("Исполнитель: ${operator.ifBlank { "не указан в настройках" }}")
                                Button(onClick={
                                    val arr = JSONArray()
                                    for (c in 0 until cps.length()) {
                                        val checkpoint = cps.getJSONObject(c)
                                        val qs = checkpoint.getJSONArray("questions")
                                        for (q in 0 until qs.length()) {
                                            val question = qs.getJSONObject(q)
                                            val id = question.getString("id")
                                            arr.put(JSONObject().put("checkpoint_id",checkpoint.getString("id")).put("question_id",id).put("question",question.getString("title")).put("value",answers[id] ?: ""))
                                        }
                                    }
                                    val payload=JSONObject().put("id",UUID.randomUUID().toString()).put("route_id",route.getString("id"))
                                        .put("route_snapshot",JSONObject(route.toString())).put("performed_at",Instant.now().toString())
                                        .put("operator",operator).put("comment",comment).put("answers",arr)
                                    store.save(payload); counts=store.counts(); message="Обход сохранён на устройстве"; page="home"
                                }, enabled=requiredOk, modifier=Modifier.fillMaxWidth()) {Text("Завершить и сохранить обход")}
                                OutlinedButton(onClick={page="home"},modifier=Modifier.fillMaxWidth()) {Text("Назад")}
                            }
                            "settings" -> {
                                Text("Настройки",style=MaterialTheme.typography.titleLarge)
                                OutlinedTextField(value=server,onValueChange={server=it},label={Text("Адрес сервера HTTPS")},modifier=Modifier.fillMaxWidth())
                                OutlinedTextField(value=token,onValueChange={token=it},label={Text("API-ключ")},modifier=Modifier.fillMaxWidth())
                                OutlinedTextField(value=operator,onValueChange={operator=it},label={Text("Имя исполнителя")},modifier=Modifier.fillMaxWidth())
                                Button(onClick={preferences.edit().putString("server",server).putString("token",token).putString("operator",operator).apply();message="Настройки сохранены"},modifier=Modifier.fillMaxWidth()) {Text("Сохранить настройки")}
                                Button(onClick={runTask {
                                    val data=request(server,token,"/api/routes")
                                    val loaded = JSONArray(data)
                                    preferences.edit().putString("routes",data).apply()
                                    runOnUiThread { routes = loaded }
                                    "Загружено маршрутов: ${loaded.length()}"
                                }},enabled=!busy,modifier=Modifier.fillMaxWidth()) {Text("Загрузить маршруты")}
                                Button(onClick={runTask {
                                    var sent=0
                                    while (true) {
                                        val items=store.unsynced()
                                        if(items.length()==0) break
                                        val response=JSONObject(request(server,token,"/api/inspections/batch",items))
                                        val ids=response.getJSONArray("saved_ids")
                                        if(ids.length()!=items.length()) throw IllegalStateException("Сервер подтвердил не все записи")
                                        store.markSynced(ids); sent+=ids.length()
                                    }
                                    "Передано обходов: $sent"
                                }},enabled=!busy,modifier=Modifier.fillMaxWidth()) {Text("Отправить результаты (${counts.second})")}
                                Text("Работа без интернета: ранее загруженные маршруты и ответы остаются на этом телефоне. Для защиты доступа сервер использует общий API-ключ; перед эксплуатацией следует добавить персональную авторизацию.")
                                OutlinedButton(onClick={page="home"},modifier=Modifier.fillMaxWidth()) {Text("Назад")}
                            }
                        }
                    }
                }
            }
        }
    }
}
