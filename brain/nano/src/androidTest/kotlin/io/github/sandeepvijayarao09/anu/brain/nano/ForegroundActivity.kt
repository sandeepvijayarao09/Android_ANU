package io.github.sandeepvijayarao09.anu.brain.nano

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager

/**
 * An empty screen that puts S10's test process in the foreground, because AICore blocks use from the background
 * (error 30). It shows over the lock screen and keeps the screen on while the tasks run.
 */
class ForegroundActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
