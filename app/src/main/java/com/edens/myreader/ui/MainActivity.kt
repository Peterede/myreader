package com.edens.myreader.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.edens.myreader.MyReaderApp

class MainActivity : ComponentActivity() {

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { ReaderApp() }
    }
}

@Composable
private fun ReaderApp() {
    val app = LocalContext.current.applicationContext as MyReaderApp
    var openBookId by rememberSaveable { mutableStateOf<String?>(null) }
    var libraryVersion by rememberSaveable { mutableStateOf(0) }

    MyReaderTheme {
        val bookId = openBookId
        if (bookId == null) {
            LibraryScreen(
                store = app.store,
                player = app.player,
                refreshKey = libraryVersion,
                onOpenBook = { openBookId = it },
            )
        } else {
            BackHandler { openBookId = null }
            ReaderScreen(
                bookId = bookId,
                player = app.player,
                onBack = {
                    libraryVersion++
                    openBookId = null
                },
            )
        }
    }
}
