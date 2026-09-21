package com.omax.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Patterns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.KeyStore

data class ChatMessage(val text: String, val fromUser: Boolean)

data class GitHubDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresIn: Long,
    val intervalSeconds: Long
)

object GitHubApi {
    private const val DEVICE_CODE_URL = "https://github.com/login/device/code"
    private const val ACCESS_TOKEN_URL = "https://github.com/login/oauth/access_token"
    private const val USER_URL = "https://api.github.com/user"
    private const val API_VERSION = "2026-03-10"

    private fun postForm(url: String, params: Map<String, String>): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.setRequestProperty("User-Agent", "OMAX")
            connection.setRequestProperty("X-GitHub-Api-Version", API_VERSION)

            val body = params.entries.joinToString("&") {
                URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
            }
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val stream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            stream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    suspend fun requestDeviceCode(clientId: String): GitHubDeviceCode = withContext(Dispatchers.IO) {
        val json = JSONObject(
            postForm(
                DEVICE_CODE_URL,
                mapOf("client_id" to clientId, "scope" to "read:user")
            )
        )
        GitHubDeviceCode(
            deviceCode = json.getString("device_code"),
            userCode = json.getString("user_code"),
            verificationUri = json.getString("verification_uri"),
            expiresIn = json.getLong("expires_in"),
            intervalSeconds = json.optLong("interval", 5L)
        )
    }

    suspend fun waitForToken(clientId: String, device: GitHubDeviceCode): String = withContext(Dispatchers.IO) {
        var interval = device.intervalSeconds.coerceAtLeast(5L)
        val deadline = System.currentTimeMillis() + device.expiresIn * 1000L

        while (System.currentTimeMillis() < deadline) {
            delay(interval * 1000L)

            val json = JSONObject(
                postForm(
                    ACCESS_TOKEN_URL,
                    mapOf(
                        "client_id" to clientId,
                        "device_code" to device.deviceCode,
                        "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"
                    )
                )
            )

            val token = json.optString("access_token")
            if (token.isNotBlank()) return@withContext token

            when (json.optString("error")) {
                "authorization_pending" -> Unit
                "slow_down" -> {
                    interval = json.optLong("interval", interval + 5L).coerceAtLeast(interval + 5L)
                }
                "expired_token" -> error("Код GitHub истёк. Запусти подключение заново.")
                "access_denied" -> error("Авторизация GitHub отменена.")
                else -> error(json.optString("error_description", "GitHub не выдал токен."))
            }
        }

        error("Время ожидания авторизации GitHub истекло.")
    }

    suspend fun getLogin(token: String): String = withContext(Dispatchers.IO) {
        val connection = URL(USER_URL).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("X-GitHub-Api-Version", API_VERSION)
            connection.setRequestProperty("User-Agent", "OMAX")

            if (connection.responseCode !in 200..299) {
                error("GitHub не подтвердил подключение.")
            }

            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                .getString("login")
        } finally {
            connection.disconnect()
        }
    }
}

class GitHubTokenStore(context: Context) {
    private val prefs = context.getSharedPreferences("omax_github", Context.MODE_PRIVATE)
    private val keyAlias = "omax_github_token_key"

    private fun getKey(): javax.crypto.SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getKey(keyAlias, null)
        if (existing is javax.crypto.SecretKey) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    fun saveToken(token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getKey())
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + encrypted
        prefs.edit().putString("token", Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
    }

    fun loadToken(): String? {
        val encoded = prefs.getString("token", null) ?: return null
        return try {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            val iv = packed.copyOfRange(0, 12)
            val encrypted = packed.copyOfRange(12, packed.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}

@Composable
fun OmaxTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(), content = content)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { OmaxTheme { OmaxApp() } }
    }
}

private fun generateOmaxReply(history: List<ChatMessage>): String {
    val last = history.lastOrNull { it.fromUser }?.text?.trim().orEmpty()
    val lower = last.lowercase()
    val previousUserMessages = history.filter { it.fromUser }.dropLast(1).takeLast(3).map { it.text }

    return when {
        last.isEmpty() -> "Я жду сообщение 🤖"
        lower.contains("привет") || lower.contains("здаров") || lower.contains("здравствуй") ->
            if (previousUserMessages.isNotEmpty()) "Привет снова! 👋 Я помню, что мы уже общались. Продолжай."
            else "Привет! 👋 Я OMAX. Что будем делать?"
        lower.contains("как дела") -> "Всё нормально 🤖 Я готов продолжать наш диалог."
        lower.contains("кто ты") || lower.contains("что ты") -> "Я OMAX — нейросеть, которую мы сейчас строим 🧠"
        lower.contains("омакс") -> "Да, я здесь 😎 Я учитываю сообщения выше в этом чате."
        lower.contains("что я") && previousUserMessages.isNotEmpty() ->
            "До этого ты писал: «" + previousUserMessages.last() + "»."
        lower.endsWith("?") ->
            "Я понял вопрос: «" + last + "».\n\nПока мой локальный мозг умеет анализировать контекст простыми правилами. Следующий шаг — подключить настоящую модель, чтобы отвечать на вопросы свободно."
        previousUserMessages.isNotEmpty() ->
            "Понял. Ты написал: «" + last + "».\n\nИ это продолжает наш предыдущий разговор: «" + previousUserMessages.last() + "» 🧠"
        else -> "Понял сообщение: «" + last + "» 🧠\nЯ обработал именно твой текст, а не выбрал случайный ответ."
    }
}

@Composable
fun AuthScreen(auth: FirebaseAuth, onLoggedIn: () -> Unit) {
    var registerMode by remember { mutableStateOf(true) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    fun finish() {
        loading = false
        error = null
        onLoggedIn()
    }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("OMAX 🤖", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        Text(if (registerMode) "Создание аккаунта" else "Вход в аккаунт")
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = email,
            onValueChange = { email = it; error = null },
            label = { Text("Email") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it; error = null },
            label = { Text("Пароль") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )

        if (registerMode) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = confirmPassword,
                onValueChange = { confirmPassword = it; error = null },
                label = { Text("Повторите пароль") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
        }

        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(20.dp))
        Button(
            enabled = !loading,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val cleanEmail = email.trim()
                when {
                    !Patterns.EMAIL_ADDRESS.matcher(cleanEmail).matches() ->
                        error = "Введите корректный email."
                    password.length < 6 ->
                        error = "Пароль должен содержать минимум 6 символов."
                    registerMode && password != confirmPassword ->
                        error = "Пароли не совпадают."
                    else -> {
                        loading = true
                        if (registerMode) {
                            auth.createUserWithEmailAndPassword(cleanEmail, password)
                                .addOnSuccessListener { finish() }
                                .addOnFailureListener {
                                    loading = false
                                    error = it.message ?: "Не удалось создать аккаунт."
                                }
                        } else {
                            auth.signInWithEmailAndPassword(cleanEmail, password)
                                .addOnSuccessListener { finish() }
                                .addOnFailureListener {
                                    loading = false
                                    error = it.message ?: "Не удалось войти."
                                }
                        }
                    }
                }
            }
        ) {
            Text(if (loading) "Подождите…" else if (registerMode) "Зарегистрироваться" else "Войти")
        }

        Spacer(Modifier.height(8.dp))
        TextButton(
            enabled = !loading,
            onClick = {
                registerMode = !registerMode
                error = null
            }
        ) {
            Text(if (registerMode) "Уже есть аккаунт? Войти" else "Нет аккаунта? Регистрация")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitHubConnectScreen(
    store: GitHubTokenStore,
    onBack: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var login by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("GitHub не подключён") }
    var error by remember { mutableStateOf<String?>(null) }
    var deviceCode by remember { mutableStateOf<GitHubDeviceCode?>(null) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val token = store.loadToken()
        if (token != null) {
            try {
                login = GitHubApi.getLogin(token)
                status = "Подключён"
            } catch (_: Exception) {
                store.clear()
                status = "GitHub не подключён"
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("GitHub") },
                navigationIcon = { TextButton(onClick = onBack) { Text("←") } }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("GitHub", style = MaterialTheme.typography.headlineMedium)
            Text(status)

            login?.let {
                Text("Аккаунт: @$it")
            }

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }

            Button(
                enabled = !loading,
                onClick = {
                    error = null
                    val clientId = BuildConfig.GITHUB_CLIENT_ID.trim()
                    if (clientId.isBlank()) {
                        error = "GitHub Client ID ещё не настроен для сборки OMAX."
                        return@Button
                    }

                    loading = true
                    scope.launch {
                        try {
                            val device = GitHubApi.requestDeviceCode(clientId)
                            deviceCode = device
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(device.verificationUri))
                            )
                            val token = GitHubApi.waitForToken(clientId, device)
                            val githubLogin = GitHubApi.getLogin(token)
                            store.saveToken(token)
                            login = githubLogin
                            status = "Подключён"
                        } catch (e: Exception) {
                            error = e.message ?: "Не удалось подключить GitHub."
                        } finally {
                            loading = false
                        }
                    }
                }
            ) {
                Text(if (loading) "Ожидание GitHub…" else "Привязать GitHub")
            }

            deviceCode?.let {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    tonalElevation = 3.dp
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Код GitHub", style = MaterialTheme.typography.titleMedium)
                        Text(it.userCode, style = MaterialTheme.typography.headlineSmall)
                        Text("В открывшемся окне GitHub введи этот код и подтверди доступ.")
                    }
                }
            }

            if (login != null) {
                OutlinedButton(
                    onClick = {
                        store.clear()
                        login = null
                        deviceCode = null
                        status = "GitHub не подключён"
                    }
                ) {
                    Text("Отключить GitHub")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OmaxApp() {
    val auth = remember { FirebaseAuth.getInstance() }
    val context = androidx.compose.ui.platform.LocalContext.current
    val githubStore = remember { GitHubTokenStore(context) }
    var loggedIn by remember { mutableStateOf(auth.currentUser != null) }

    if (!loggedIn) {
        AuthScreen(auth = auth, onLoggedIn = { loggedIn = true })
        return
    }

    var input by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var showGitHub by remember { mutableStateOf(false) }
    var showNewChat by remember { mutableStateOf(false) }
    var isThinking by remember { mutableStateOf(false) }
    var messages by remember { mutableStateOf(listOf(ChatMessage("Привет! Я OMAX 🤖", false))) }

    if (showGitHub) {
        GitHubConnectScreen(store = githubStore, onBack = { showGitHub = false })
        return
    }

    if (showNewChat) {
        AlertDialog(
            onDismissRequest = { showNewChat = false },
            title = { Text("Новый чат") },
            text = { Text("Создать новый пустой диалог с OMAX?") },
            confirmButton = {
                TextButton(onClick = {
                    messages = listOf(ChatMessage("Новый чат. Я OMAX 🤖", false))
                    showNewChat = false
                }) { Text("Создать") }
            },
            dismissButton = { TextButton(onClick = { showNewChat = false }) { Text("Отмена") } }
        )
    }

    if (showSettings) {
        SettingsScreen(
            email = auth.currentUser?.email.orEmpty(),
            onGitHub = { showGitHub = true },
            onSignOut = {
                auth.signOut()
                loggedIn = false
            },
            onBack = { showSettings = false }
        )
        return
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("OMAX", style = MaterialTheme.typography.titleLarge)
                        Text(if (isThinking) "Думает…" else "AI", style = MaterialTheme.typography.labelSmall)
                    }
                },
                actions = {
                    TextButton(onClick = { showNewChat = true }) { Text("+") }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Настройки")
                    }
                }
            )
        },
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Напиши OMAX...") },
                    shape = RoundedCornerShape(22.dp),
                    singleLine = true,
                    enabled = !isThinking
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    enabled = !isThinking,
                    onClick = {
                        val userText = input.trim()
                        if (userText.isNotBlank()) {
                            messages = messages + ChatMessage(userText, true)
                            input = ""
                            isThinking = true
                        }
                    }
                ) { Text("➤") }
            }
        }
    ) { padding ->
        LaunchedEffect(isThinking) {
            if (isThinking) {
                delay(700)
                messages = messages + ChatMessage(generateOmaxReply(messages), false)
                isThinking = false
            }
        }

        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(messages) { message ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.Start
                ) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        tonalElevation = 3.dp,
                        modifier = Modifier.widthIn(max = 360.dp)
                    ) {
                        Text(message.text, Modifier.padding(14.dp))
                    }
                }
            }
            if (isThinking) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                        Surface(shape = RoundedCornerShape(18.dp), tonalElevation = 3.dp) {
                            Text("OMAX думает… 🧠", Modifier.padding(14.dp))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    email: String,
    onGitHub: () -> Unit,
    onSignOut: () -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = { TextButton(onClick = onBack) { Text("←") } }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("OMAX", style = MaterialTheme.typography.headlineMedium)
            Text("Аккаунт", style = MaterialTheme.typography.titleMedium)
            Text(email.ifBlank { "Email не указан" })
            HorizontalDivider()

            Text("GitHub", style = MaterialTheme.typography.titleMedium)
            Text("Привяжи GitHub-аккаунт к OMAX.")
            Button(onClick = onGitHub) { Text("Привязать GitHub") }

            HorizontalDivider()
            Text("Тёмная тема", style = MaterialTheme.typography.titleMedium)
            Text("Сейчас включена автоматически.", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onSignOut) { Text("Выйти из аккаунта") }
            Text("Версия 0.1.0", style = MaterialTheme.typography.bodySmall)
        }
    }
}
