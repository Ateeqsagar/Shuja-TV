package com.nova.tv

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Process
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

/**
 * Last line of defence: if anything still throws, the error is saved (Settings > Last crash log)
 * and the app relaunches itself instead of leaving the TV on the home screen.
 * A 20 second guard prevents restart loops.
 */
class App : Application() {
    /** One shared state for the whole life of the app process, so reopening the app does not reload everything. */
    val vm: Vm by lazy { Vm(this) }

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                val now = System.currentTimeMillis()
                val sp = getSharedPreferences("nova_crash", 0)
                val last = sp.getLong("t", 0L)
                sp.edit().putString("log", ("" + java.util.Date(now) + "\n" + sw.toString()).take(6000)).putLong("t", now).commit()
                if (now - last > 20_000) {
                    val i = Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    val pi = PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT)
                    val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
                    am.set(AlarmManager.RTC, now + 700, pi)
                    Process.killProcess(Process.myPid())
                    exitProcess(10)
                }
            } catch (t: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
