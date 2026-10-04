package com.xaulinxs.aosp.browser

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Process

// XAULINXS_WV_RELOAD_V1
/**
 * Roda no processo separado ":restart" (ver AndroidManifest), por isso
 * sobrevive a morte do processo principal. Abre a MainActivity de novo e
 * mata o processo principal, para o proximo processo nascer limpo.
 * Sem interface propria (tema translucido do framework).
 */
class RestartActivity : Activity() {

    companion object {
        const val EXTRA_MAIN_PID = "xaulinxs_main_pid"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mainPid = intent.getIntExtra(EXTRA_MAIN_PID, -1)

        val next = Intent(this, MainActivity::class.java)
        next.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        try {
            startActivity(next)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        finish()
        overridePendingTransition(0, 0)

        if (mainPid > 0 && mainPid != Process.myPid()) {
            Process.killProcess(mainPid)
        }
        Process.killProcess(Process.myPid())
    }
}
