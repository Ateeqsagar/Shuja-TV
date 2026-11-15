package com.nova.tv

import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class MainActivity : ComponentActivity() {
    private val vm: Vm get() = (application as App).vm

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val last = getSharedPreferences("nova_crash", 0).getLong("t", 0L)
        if (savedInstanceState == null && System.currentTimeMillis() - last < 20_000) {
            Toast.makeText(this, "Shuja TV recovered from an error. Details: Settings > Last crash log", Toast.LENGTH_LONG).show()
        }
        if (savedInstanceState == null) vm.onColdLaunch()
        setContent { NovaApp(vm) { vm.markClosed(); finishAndRemoveTask() } }
    }

    override fun onStart() {
        super.onStart()
        vm.refreshIfStale()
    }

    override fun onStop() {
        super.onStop()
        vm.relock()
    }
}
