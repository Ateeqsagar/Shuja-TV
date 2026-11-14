package com.nova.tv

import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels

class MainActivity : ComponentActivity() {
    private val vm: Vm by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val last = getSharedPreferences("nova_crash", 0).getLong("t", 0L)
        if (savedInstanceState == null && System.currentTimeMillis() - last < 20_000) {
            Toast.makeText(this, "Shuja TV recovered from an error. Details: Settings > Last crash log", Toast.LENGTH_LONG).show()
        }
        setContent { NovaApp(vm) }
    }

    override fun onStop() {
        super.onStop()
        vm.relock()
    }
}
