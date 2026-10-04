package com.xaulinxs.aosp.browser

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Process

// XAULINXS_WV_RELOAD_V1
/**
 * Recarga manual do WebView embutido: apaga a copia antiga do apk e copia de
 * novo o aosp_webview.apk que esta dentro do app (assets).
 *
 * O trabalho de apagar/recopiar acontece em BrowserApplication.onCreate() do
 * processo NOVO (nada usa o apk nesse momento). Aqui so gravamos o pedido e
 * reiniciamos o processo - o provider do WebView so e resolvido uma vez por
 * processo, entao sem reiniciar a troca nao teria efeito.
 */
object WebViewReloader {

    private const val PREFS = "xaulinxs_webview_reload"
    private const val KEY_FORCE_RECOPY = "force_recopy"

    /** O apk precisa estar dentro do app, senao apagar a copia deixaria o app sem WebView embutido. */
    fun isBundledApkAvailable(context: Context): Boolean {
        return try {
            context.assets.openFd(BrowserApplication.WEBVIEW_ASSET_NAME).use { it.declaredLength > 0 }
        } catch (e: Exception) {
            false
        }
    }

    /** Grava o pedido e reinicia o app. Devolve false se nao conseguiu gravar o pedido. */
    fun reloadAndRestart(activity: Activity): Boolean {
        val saved = activity.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_FORCE_RECOPY, true)
            .commit()
        if (!saved) return false

        val intent = Intent(activity, RestartActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        intent.putExtra(RestartActivity.EXTRA_MAIN_PID, Process.myPid())
        activity.startActivity(intent)
        return true
    }

    /** Chamado por BrowserApplication (processo principal). Devolve true uma unica vez por pedido. */
    fun consumeRecopyRequest(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_FORCE_RECOPY, false)) return false
        prefs.edit().remove(KEY_FORCE_RECOPY).commit()
        return true
    }
}
