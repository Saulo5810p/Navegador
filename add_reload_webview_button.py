#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
add_reload_webview_button.py   (marcador: XAULINXS_WV_RELOAD_V1)

Uso (na raiz do repo Navegador, no Termux):
    python3 add_reload_webview_button.py
ou: python3 add_reload_webview_button.py /caminho/do/Navegador

O que faz
---------
Adiciona em Configuracoes o item "Recarregar WebView (recopiar apk embutido)".
Ao confirmar no popup:
  1) grava um pedido de recopia (SharedPreferences, commit sincrono);
  2) reinicia o processo do app (RestartActivity, num processo separado
     ":restart", mesmo padrao do ProcessPhoenix) - necessario porque o
     provider do WebView so e resolvido UMA vez por processo;
  3) no processo novo, BrowserApplication.onCreate() ve o pedido e chama
     upgradeSource.delete() (apaga filesDir/aosp_webview.apk, a pasta de libs
     nativas extraidas e a flag "ja copiei" da lib) ANTES de WebViewUpgrade
     .upgrade(), que entao copia de novo o assets/aosp_webview.apk atual.
Apagar no processo novo (e nao no que esta rodando) evita mexer num apk que o
WebView atual ainda esta usando. Antes de agendar, o botao confere se o
aosp_webview.apk existe nos assets e recusa se nao existir (para nao apagar
a copia e ficar sem WebView embutido).

A deteccao automatica por tamanho em BrowserApplication continua como estava;
o botao e a forma manual de forcar.

Idempotente: rodar duas vezes nao duplica nada.
"""
import os
import sys

MARK = "XAULINXS_WV_RELOAD_V1"

ROOT = sys.argv[1] if len(sys.argv) > 1 else os.getcwd()
MAIN = os.path.join(ROOT, "app", "src", "main")
BROWSER = os.path.join(MAIN, "java", "com", "xaulinxs", "aosp", "browser")


def read(path):
    with open(path, "r", encoding="utf-8") as f:
        return f.read()


def write(path, text):
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


def need(path):
    if not os.path.isfile(path):
        sys.exit("ERRO: arquivo nao encontrado: %s\n(rode na raiz do repo Navegador)" % path)


RELOADER_KT = '''package com.xaulinxs.aosp.browser

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
'''

RESTART_KT = '''package com.xaulinxs.aosp.browser

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
'''

STRINGS_BLOCK = '''    <!-- XAULINXS_WV_RELOAD_V1 -->
    <string name="settings_reload_webview">Recarregar WebView (recopiar apk embutido)</string>
    <string name="webview_reload_title">Recarregar WebView</string>
    <string name="webview_reload_message">A cópia atual do WebView será apagada e o apk embutido no app será copiado de novo. O navegador vai reiniciar.</string>
    <string name="webview_reload_confirm">Recarregar</string>
    <string name="webview_reload_missing_apk">aosp_webview.apk não foi encontrado dentro do app</string>
    <string name="webview_reload_failed">Não foi possível agendar a recópia</string>
'''

LAYOUT_BLOCK = '''
        <View
            android:layout_width="match_parent"
            android:layout_height="1dp"
            android:background="#E0E0E0"
            android:layout_marginTop="8dp"
            android:layout_marginBottom="8dp" />

        <!-- XAULINXS_WV_RELOAD_V1: apaga a copia antiga do WebView e recopia
             o apk embutido (reinicia o app). -->
        <TextView
            android:id="@+id/menuReloadWebView"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="@string/settings_reload_webview"
            android:textSize="16sp"
            android:padding="14dp"
            android:clickable="true"
            android:focusable="true"
            android:background="?android:attr/selectableItemBackground" />

'''

MANIFEST_BLOCK = '''
        <!-- XAULINXS_WV_RELOAD_V1: roda em processo proprio para sobreviver
             ao reinicio do processo principal (recarga do WebView). -->
        <activity
            android:name=".RestartActivity"
            android:process=":restart"
            android:exported="false"
            android:excludeFromRecents="true"
            android:theme="@android:style/Theme.Translucent.NoTitleBar" />
'''

SETTINGS_LISTENER = '''
        // XAULINXS_WV_RELOAD_V1
        findViewById<TextView>(R.id.menuReloadWebView).setOnClickListener {
            confirmReloadWebView()
        }
'''

SETTINGS_METHOD = '''    // XAULINXS_WV_RELOAD_V1
    /**
     * Confirma e dispara a recarga do WebView embutido: apaga a copia antiga
     * e recopia o aosp_webview.apk dos assets (o app reinicia).
     */
    private fun confirmReloadWebView() {
        if (!WebViewReloader.isBundledApkAvailable(this)) {
            Toast.makeText(this, R.string.webview_reload_missing_apk, Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.webview_reload_title)
            .setMessage(R.string.webview_reload_message)
            .setPositiveButton(R.string.webview_reload_confirm) { _, _ ->
                if (!WebViewReloader.reloadAndRestart(this)) {
                    Toast.makeText(this, R.string.webview_reload_failed, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

'''

APP_OLD = """            if (bundledWebViewApkChanged()) {
                upgradeSource.delete()
            }
"""
APP_NEW = """            // XAULINXS_WV_RELOAD_V1: o botao "Recarregar WebView" das
            // Configuracoes grava um pedido e reinicia o app; aqui, no
            // processo novo e antes de qualquer uso do apk, apagamos a copia
            // antiga (apk + libs nativas + flag "ja copiei") para o
            // upgrade() copiar de novo o aosp_webview.apk dos assets.
            // As duas chamadas sao avaliadas sempre (sem curto-circuito),
            // para o fingerprint de tamanho tambem ser atualizado.
            val forceRecopy = WebViewReloader.consumeRecopyRequest(this)
            val apkChanged = bundledWebViewApkChanged()
            if (forceRecopy || apkChanged) {
                upgradeSource.delete()
            }
"""


def patch_new_files():
    for name, body in (("WebViewReloader.kt", RELOADER_KT), ("RestartActivity.kt", RESTART_KT)):
        path = os.path.join(BROWSER, name)
        if os.path.isfile(path) and MARK in read(path):
            print("= %s ja existe" % name)
        else:
            write(path, body)
            print("+ %s criado" % name)


def patch_strings():
    path = os.path.join(MAIN, "res", "values", "strings_funcoes.xml")
    need(path)
    s = read(path)
    if MARK in s:
        print("= strings_funcoes.xml ja atualizado")
        return
    idx = s.rfind("</resources>")
    if idx < 0:
        sys.exit("ERRO: </resources> nao encontrado em strings_funcoes.xml")
    write(path, s[:idx] + STRINGS_BLOCK + s[idx:])
    print("+ strings_funcoes.xml atualizado")


def patch_layout():
    path = os.path.join(MAIN, "res", "layout", "activity_settings.xml")
    need(path)
    s = read(path)
    if MARK in s:
        print("= activity_settings.xml ja atualizado")
        return
    anchor = "    </LinearLayout>\n</ScrollView>"
    idx = s.rfind(anchor)
    if idx < 0:
        sys.exit("ERRO: fim do LinearLayout/ScrollView nao encontrado em activity_settings.xml (mudou?)")
    write(path, s[:idx].rstrip("\n") + "\n" + LAYOUT_BLOCK + s[idx:])
    print("+ activity_settings.xml atualizado")


def patch_manifest():
    path = os.path.join(MAIN, "AndroidManifest.xml")
    need(path)
    s = read(path)
    if MARK in s:
        print("= AndroidManifest.xml ja atualizado")
        return
    anchor = """        <activity
            android:name=".AdBlockListActivity"
            android:label="@string/adblock_list_title"
            android:exported="false" />
"""
    if anchor not in s:
        sys.exit("ERRO: bloco da AdBlockListActivity nao encontrado no AndroidManifest.xml (mudou?)")
    write(path, s.replace(anchor, anchor + MANIFEST_BLOCK, 1))
    print("+ AndroidManifest.xml atualizado")


def patch_settings():
    path = os.path.join(BROWSER, "SettingsActivity.kt")
    need(path)
    s = read(path)
    if MARK in s:
        print("= SettingsActivity.kt ja atualizado")
        return

    l_anchor = """        findViewById<TextView>(R.id.menuDefaultBrowser).setOnClickListener {
            requestDefaultBrowserRole()
        }
"""
    if l_anchor not in s:
        sys.exit("ERRO: listener do menuDefaultBrowser nao encontrado em SettingsActivity.kt (mudou?)")
    s = s.replace(l_anchor, l_anchor + SETTINGS_LISTENER, 1)

    m_anchor = "    private fun setupThemeSelector() {"
    if m_anchor not in s:
        sys.exit("ERRO: setupThemeSelector nao encontrado em SettingsActivity.kt (mudou?)")
    s = s.replace(m_anchor, SETTINGS_METHOD + m_anchor, 1)
    write(path, s)
    print("+ SettingsActivity.kt atualizado")


def patch_application():
    path = os.path.join(BROWSER, "BrowserApplication.kt")
    need(path)
    s = read(path)
    if MARK in s:
        print("= BrowserApplication.kt ja atualizado")
        return
    if APP_OLD not in s:
        sys.exit("ERRO: bloco 'if (bundledWebViewApkChanged())' nao encontrado em BrowserApplication.kt (mudou?)")
    write(path, s.replace(APP_OLD, APP_NEW, 1))
    print("+ BrowserApplication.kt atualizado")


def main():
    for p in (
        os.path.join(MAIN, "res", "values", "strings_funcoes.xml"),
        os.path.join(MAIN, "res", "layout", "activity_settings.xml"),
        os.path.join(MAIN, "AndroidManifest.xml"),
        os.path.join(BROWSER, "SettingsActivity.kt"),
        os.path.join(BROWSER, "BrowserApplication.kt"),
    ):
        need(p)
    patch_new_files()
    patch_strings()
    patch_layout()
    patch_manifest()
    patch_settings()
    patch_application()
    print("\nPronto. Agora: ./gradlew assembleDebug")


if __name__ == "__main__":
    main()
