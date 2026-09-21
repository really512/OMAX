package com.omax.app

import android.os.Bundle
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

data class ChatMessage(val text: String, val fromUser: Boolean)

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
            else "Привет! 👋 Я Омакс. Что будем делать?"
        lower.contains("как дела") -> "Всё нормально 🤖 Я готов продолжать наш диалог."
        lower.contains("кто ты") || lower.contains("что ты") -> "Я Омакс — нейросеть, которую мы сейчас строим 🧠"
        lower.contains("омакс") -> "Да, я здесь 😎 Я учитываю сообщения выше в этом чате."
        lower.contains("что я") && previousUserMessages.isNotEmpty() ->
            "До этого ты писал: «" + previousUserMessages.last() + "»."
        lower.endsWith("?") ->
            "Я понял вопрос: «" + last + "».

Пока мой локальный мозг умеет анализировать контекст простыми правилами. Следующий шаг — подключить настоящую модель, чтобы отвечать на вопросы свободно."
        previousUserMessages.isNotEmpty() ->
            "Понял. Ты написал: «" + last + "».

И это продолжает наш предыдущий разговор: «" + previousUserMessages.last() + "» 🧠"
        else -> "Понял сообщение: «" + last + "» 🧠
Я обработал именно твой текст, а не выбрал случайный ответ."
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OmaxApp() {
    var input by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var showNewChat by remember { mutableStateOf(false) }
    var isThinking by remember { mutableStateOf(false) }
    var messages by remember { mutableStateOf(listOf(ChatMessage("Привет! Я Омакс 🤖", false))) }

    if (showNewChat) {
        AlertDialog(
            onDismissRequest = { showNewChat = false },
            title = { Text("Новый чат") },
            text = { Text("Создать новый пустой диалог с Омаксом?") },
            confirmButton = {
                TextButton(onClick = {
                    messages = listOf(ChatMessage("Новый чат. Я Омакс 🤖", false))
                    showNewChat = false
                }) { Text("Создать") }
            },
            dismissButton = { TextButton(onClick = { showNewChat = false }) { Text("Отмена") } }
        )
    }

    if (showSettings) {
        SettingsScreen(onBack = { showSettings = false })
        return
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Омакс", style = MaterialTheme.typography.titleLarge)
                        Text(if (isThinking) "Думает…" else "Нейросеть", style = MaterialTheme.typography.labelSmall)
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
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Напиши Омаксу...") },
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
                    ) { Text(message.text, Modifier.padding(14.dp)) }
                }
            }
            if (isThinking) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                        Surface(shape = RoundedCornerShape(18.dp), tonalElevation = 3.dp) {
                            Text("Омакс думает… 🧠", Modifier.padding(14.dp))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
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
            Text("Омакс", style = MaterialTheme.typography.headlineMedium)
            Text("Настройки приложения", style = MaterialTheme.typography.bodyLarge)
            HorizontalDivider()
            Text("Тёмная тема", style = MaterialTheme.typography.titleMedium)
            Text("Сейчас включена автоматически.", style = MaterialTheme.typography.bodyMedium)
            Text("Версия 0.1.0", style = MaterialTheme.typography.bodySmall)
        }
    }
}
