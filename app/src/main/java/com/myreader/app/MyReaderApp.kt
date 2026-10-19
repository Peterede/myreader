package com.myreader.app

import android.app.Application
import com.myreader.app.data.BookStore
import com.myreader.app.service.PlayerService
import com.myreader.app.tts.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MyReaderApp : Application() {

    val store: BookStore by lazy { BookStore(this) }
    val player: Player by lazy { Player(this, store) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        // Keep narration alive in the background: while the player reports
        // playback, a foreground service holds the process at high priority.
        appScope.launch {
            player.state.collectLatest { st ->
                if (st.isPlaying && st.bookId != null) {
                    PlayerService.start(this@MyReaderApp)
                } else if (st.bookId == null) {
                    PlayerService.stop(this@MyReaderApp)
                }
            }
        }
    }
}
