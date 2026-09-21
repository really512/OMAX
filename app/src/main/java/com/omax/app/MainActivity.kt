package com.omax.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class ChatMessage(val text: String, val fromUser: Boolean)

@Composable
fun OmaxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = MaterialTheme.colorScheme.primary
        ),
        content = content
    )
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { OmaxTheme { OmaxApp() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OmaxApp() {
    var input by remember { mutableStateOf("") }
    var messages by remember {
        mutableStateOf(listOf(ChatMessage("Привет! Я Омакс 🤖", false)))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Омакс") },
                actions = { TextButton(onClick = {}) { Text("⚙") } }
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Напиши Омаксу...") },
                    shape = RoundedCornerShape(20.dp),
                    singleLine = true
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (input.isNotBlank()) {
                            messages = messages + ChatMessage(input.trim(), true)
                            input = ""
                        }
                    },
                    shape = RoundedCornerShape(20.dp)
                ) { Text("➤") }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(messages) { message ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.Start
                ) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        tonalElevation = 3.dp,
                        modifier = Modifier.widthIn(max = 320.dp)
                    ) {
                        Text(message.text, modifier = Modifier.padding(14.dp))
                    }
                }
            }
        }
    }
}
