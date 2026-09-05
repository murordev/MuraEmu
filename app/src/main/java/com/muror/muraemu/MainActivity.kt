package com.muror.muraemu

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                EmulatorApp()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Stack.stop(this) {}
    }
}

@Composable
fun EmulatorApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var guestViewRef by remember { mutableStateOf<GuestView?>(null) }
    var isUnpacking by remember { mutableStateOf(false) }

    fun log(msg: String) {
        Log.i("MuraEmu", msg)
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            Input.serve(context) { msg -> log(msg) }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Black,
        topBar = {
            Surface(
                color = Color(0xFF181818),
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                log(">>> Пуск")
                                if (!Payload.isReady(context)) {
                                    isUnpacking = true
                                    Payload.unpack(context) { log(it) }
                                    isUnpacking = false
                                }
                                guestViewRef?.start()
                                Stack.boot(context) { log(it) }
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("Пуск", color = Color(0xFF81C784), fontSize = 12.sp)
                    }

                    OutlinedButton(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                log(">>> Стоп")
                                Stack.stop(context) { log(it) }
                                guestViewRef?.stop()
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("Стоп", color = Color(0xFFE57373), fontSize = 12.sp)
                    }

                    OutlinedButton(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                if (!Payload.isReady(context)) {
                                    isUnpacking = true
                                    Payload.unpack(context) { log(it) }
                                    isUnpacking = false
                                }
                                Runner.gate(context) { log(it) }
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text("Тест", color = Color(0xFFE0E0E0), fontSize = 12.sp)
                    }

                    OutlinedButton(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                guestViewRef?.start()
                                Runner.run(context, listOf("/system/bin/fbpaint", "5"), { log(it) })
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text("Экран", color = Color(0xFFE0E0E0), fontSize = 12.sp)
                    }

                    OutlinedButton(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                Input.key(Input.KEY_MENU)
                                kotlinx.coroutines.delay(100L)
                                Input.key(Input.KEY_MENU)
                                kotlinx.coroutines.delay(100L)
                                Input.key(28) // ENTER / OK
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text("Разблок", color = Color(0xFFCE93D8), fontSize = 12.sp)
                    }

                    OutlinedButton(
                        onClick = {
                            guestViewRef?.clear()
                            log("Экран сброшен")
                        },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text("Сброс", color = Color(0xFFE0E0E0), fontSize = 12.sp)
                    }
                }
            }
        },
        bottomBar = {
            Surface(
                color = Color(0xFF141414),
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // --- РЯД СИСТЕМНЫХ АППАРАТНЫХ КЛАВИШ ---
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { Input.key(Input.KEY_HOME) },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text("⌂ Домой", color = Color(0xFFE0E0E0), fontSize = 11.sp)
                        }

                        OutlinedButton(
                            onClick = { Input.key(Input.KEY_BACK) },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text("◀ Назад", color = Color(0xFFE0E0E0), fontSize = 11.sp)
                        }

                        OutlinedButton(
                            onClick = { Input.key(Input.KEY_MENU) },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text("☰ Меню", color = Color(0xFFE0E0E0), fontSize = 11.sp)
                        }

                        OutlinedButton(
                            onClick = { Input.key(Input.KEY_SEARCH) },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text("🔍 Поиск", color = Color(0xFFE0E0E0), fontSize = 11.sp)
                        }

                        OutlinedButton(
                            onClick = { Input.key(Input.KEY_POWER) },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text("⚡ Питание", color = Color(0xFFE0E0E0), fontSize = 11.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // --- РЕТРО ТРЕКБОЛ / D-PAD (КРЕСТОВИНА НАВИГАЦИИ) ---
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(bottom = 2.dp)
                    ) {
                        // Вверх
                        OutlinedButton(
                            onClick = { Input.key(103) }, // KEY_UP
                            modifier = Modifier.size(width = 64.dp, height = 32.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("▲", color = Color(0xFFE0E0E0), fontSize = 13.sp)
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            // Влево
                            OutlinedButton(
                                onClick = { Input.key(105) }, // KEY_LEFT
                                modifier = Modifier.size(width = 64.dp, height = 34.dp),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text("◀", color = Color(0xFFE0E0E0), fontSize = 13.sp)
                            }

                            Spacer(modifier = Modifier.width(6.dp))

                            // Центр / Клик трекбола (OK)
                            Button(
                                onClick = { Input.key(28) }, // KEY_ENTER / DPAD_CENTER
                                modifier = Modifier.size(46.dp),
                                shape = CircleShape,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E2E2E)),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text("●", color = Color.White, fontSize = 16.sp)
                            }

                            Spacer(modifier = Modifier.width(6.dp))

                            // Вправо
                            OutlinedButton(
                                onClick = { Input.key(106) }, // KEY_RIGHT
                                modifier = Modifier.size(width = 64.dp, height = 34.dp),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text("▶", color = Color(0xFFE0E0E0), fontSize = 13.sp)
                            }
                        }

                        // Вниз
                        OutlinedButton(
                            onClick = { Input.key(108) }, // KEY_DOWN
                            modifier = Modifier.size(width = 64.dp, height = 32.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("▼", color = Color(0xFFE0E0E0), fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                factory = { ctx ->
                    GuestView(ctx).apply {
                        onLog = { msg -> log(msg) }
                        guestViewRef = this
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            if (isUnpacking) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xDD000000)),
                    modifier = Modifier.padding(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(color = Color(0xFF81C784))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Распаковка файлов...", color = Color.White, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}