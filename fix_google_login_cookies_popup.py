#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
fix_google_login_cookies_popup.py

Uso (dentro do repo clonado no Termux):
    cd ~/Navegador
    python3 fix_google_login_cookies_popup.py
  ou:
    python3 fix_google_login_cookies_popup.py /caminho/do/Navegador

Implementa (User-Agent NAO e alterado):
  1. Cookies ativaveis/desativaveis nas Configuracoes:
       - "Aceitar cookies"        (CookieManager.setAcceptCookie)
       - "Cookies de terceiros"   (CookieManager.setAcceptThirdPartyCookies)
     Novo CookiePrefsManager.kt; padrao = ligado (o login do Google precisa).
  2. Multiplas janelas (causa da tela branca em /gsi/transform):
       - setSupportMultipleWindows(true) + javaScriptCanOpenWindowsAutomatically
       - onCreateWindow: window.open() vira um popup REAL (Dialog com WebView
         propria, mantendo window.opener/postMessage, que o GSI exige).
         Links target=_blank abrem na WebView atual. Popups sem gesto do
         usuario sao recusados (mini bloqueador de pop-up).
       - onCloseWindow fecha o popup.
  3. AdBlock com lista de permissao: accounts.google.com, apis.google.com,
     ssl/www.gstatic.com, challenges.cloudflare.com, recaptcha.net e os
     caminhos /gsi/ e /recaptcha/ de google.com nunca sao bloqueados.
  4. databaseEnabled = true e CookieManager.flush() no onPause.

Funciona com ou sem o script fix_browser_blob_cleartext_selection_video.py
ja aplicado. Idempotente.
"""
import os
import sys

MARK = "XAULINXS_FIX_V3_LOGIN"

root = sys.argv[1] if len(sys.argv) > 1 else os.getcwd()
MAIN = os.path.join(root, "app/src/main")
KT_MAIN = os.path.join(MAIN, "java/com/xaulinxs/aosp/browser/MainActivity.kt")
KT_SETTINGS = os.path.join(MAIN, "java/com/xaulinxs/aosp/browser/SettingsActivity.kt")
KT_ADBLOCK = os.path.join(MAIN, "java/com/xaulinxs/funcoes/AdBlockManager.kt")
KT_COOKIES = os.path.join(MAIN, "java/com/xaulinxs/funcoes/CookiePrefsManager.kt")
LAYOUT = os.path.join(MAIN, "res/layout/activity_settings.xml")
STRINGS = os.path.join(MAIN, "res/values/strings.xml")

for p in (KT_MAIN, KT_SETTINGS, KT_ADBLOCK, LAYOUT, STRINGS):
    if not os.path.isfile(p):
        sys.exit("ERRO: arquivo nao encontrado: %s\nRode dentro da raiz do repo Navegador." % p)


def read(p):
    with open(p, encoding="utf-8") as f:
        return f.read()


def write(p, s):
    with open(p, "w", encoding="utf-8", newline="\n") as f:
        f.write(s)


def must_replace(src, old, new, label):
    if old not in src:
        sys.exit("ERRO: ancora nao encontrada para: %s" % label)
    return src.replace(old, new, 1)


# ---------------------------------------------------------------------------
# 1) CookiePrefsManager.kt
# ---------------------------------------------------------------------------
COOKIES_KT = r'''package com.xaulinxs.funcoes

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView

/**
 * XAULINXS_FIX_V3_LOGIN
 *
 * Preferências de cookies (Configurações). Padrão: tudo LIGADO, porque
 * login (inclusive o Google Sign-In/GSI em sites de terceiros) depende de
 * cookies e de cookies entre domínios.
 *
 * setAcceptCookie é global do processo (vale na hora); o de terceiros é
 * por WebView, então a MainActivity reaplica em onResume e em cada
 * WebView/popup que cria.
 */
object CookiePrefsManager {

    private const val PREFS_NAME = "xaulinxs_browser_prefs"
    private const val KEY_COOKIES = "cookies_enabled"
    private const val KEY_THIRD_PARTY = "cookies_third_party_enabled"

    fun isCookiesEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_COOKIES, true)

    fun setCookiesEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_COOKIES, enabled).apply()
        CookieManager.getInstance().setAcceptCookie(enabled)
    }

    fun isThirdPartyEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_THIRD_PARTY, true)

    fun setThirdPartyEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_THIRD_PARTY, enabled).apply()
    }

    /** Aplica as duas preferências (global + a WebView informada, se houver). */
    fun apply(context: Context, webView: WebView?) {
        val manager = CookieManager.getInstance()
        val cookies = isCookiesEnabled(context)
        manager.setAcceptCookie(cookies)
        if (webView != null) {
            manager.setAcceptThirdPartyCookies(webView, cookies && isThirdPartyEnabled(context))
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
'''
if os.path.isfile(KT_COOKIES) and MARK in read(KT_COOKIES):
    print("[skip] CookiePrefsManager.kt ja existe")
else:
    write(KT_COOKIES, COOKIES_KT)
    print("[ok] CookiePrefsManager.kt criado")

# ---------------------------------------------------------------------------
# 2) strings
# ---------------------------------------------------------------------------
s = read(STRINGS)
if "settings_cookies_title" in s:
    print("[skip] strings ja adicionadas")
else:
    add = (
        '    <!-- ' + MARK + ' -->\n'
        '    <string name="settings_cookies_title">Aceitar cookies</string>\n'
        '    <string name="settings_cookies_subtitle">Necessários para manter você logado nos sites</string>\n'
        '    <string name="settings_cookies_third_title">Cookies de terceiros</string>\n'
        '    <string name="settings_cookies_third_subtitle">Necessários para o login com Google em outros sites</string>\n'
    )
    s = must_replace(s, "</resources>", add + "</resources>", "strings </resources>")
    write(STRINGS, s)
    print("[ok] strings adicionadas")

# ---------------------------------------------------------------------------
# 3) layout: duas linhas de switch antes do bloco do Adblock
# ---------------------------------------------------------------------------
lay = read(LAYOUT)
if "cookiesSwitch" in lay:
    print("[skip] layout ja com switches de cookies")
else:
    def row(title, sub, sid, last):
        return '''        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="horizontal"
            android:gravity="center_vertical"
            android:layout_marginBottom="%s">

            <LinearLayout
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:orientation="vertical">

                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="@string/%s"
                    android:textSize="16sp" />

                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="@string/%s"
                    android:textSize="13sp"
                    android:textColor="#616161"
                    android:layout_marginTop="2dp" />
            </LinearLayout>

            <Switch
                android:id="@+id/%s"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content" />
        </LinearLayout>

''' % ("16dp" if not last else "24dp", title, sub, sid)

    block = (
        "        <!-- " + MARK + ": cookies (Aceitar / Terceiros) -->\n"
        + row("settings_cookies_title", "settings_cookies_subtitle", "cookiesSwitch", False)
        + row("settings_cookies_third_title", "settings_cookies_third_subtitle", "thirdPartyCookiesSwitch", True)
        + '''        <View
            android:layout_width="match_parent"
            android:layout_height="1dp"
            android:background="#E0E0E0"
            android:layout_marginBottom="16dp" />

'''
    )
    lay = must_replace(
        lay,
        "        <!-- Adblock embutido (ver AdBlockManager.kt)",
        block + "        <!-- Adblock embutido (ver AdBlockManager.kt)",
        "layout bloco adblock",
    )
    write(LAYOUT, lay)
    print("[ok] layout: switches de cookies")

# ---------------------------------------------------------------------------
# 4) SettingsActivity
# ---------------------------------------------------------------------------
st = read(KT_SETTINGS)
if MARK in st:
    print("[skip] SettingsActivity ja corrigida")
else:
    st = must_replace(
        st,
        "import com.xaulinxs.funcoes.AdBlockManager\n",
        "import com.xaulinxs.funcoes.AdBlockManager\nimport com.xaulinxs.funcoes.CookiePrefsManager\n",
        "import CookiePrefsManager",
    )
    st = must_replace(
        st,
        "        setupAdBlockSwitch()\n",
        "        setupAdBlockSwitch()\n        setupCookieSwitches() // " + MARK + "\n",
        "chamada setupAdBlockSwitch",
    )
    func = '''    /**
     * ''' + MARK + '''
     * "Aceitar cookies" liga/desliga tudo; "Cookies de terceiros" só faz
     * sentido com o primeiro ligado (fica desabilitado quando não).
     * O efeito é imediato: o global vale na hora e o de terceiros é
     * reaplicado na WebView quando o usuário volta ao navegador
     * (MainActivity.onResume). Recarregue a página para o site enxergar.
     */
    private fun setupCookieSwitches() {
        val cookies = findViewById<Switch>(R.id.cookiesSwitch)
        val third = findViewById<Switch>(R.id.thirdPartyCookiesSwitch)

        cookies.isChecked = CookiePrefsManager.isCookiesEnabled(this)
        third.isChecked = CookiePrefsManager.isThirdPartyEnabled(this)
        third.isEnabled = cookies.isChecked

        cookies.setOnCheckedChangeListener { _, isChecked ->
            CookiePrefsManager.setCookiesEnabled(this, isChecked)
            third.isEnabled = isChecked
        }
        third.setOnCheckedChangeListener { _, isChecked ->
            CookiePrefsManager.setThirdPartyEnabled(this, isChecked)
        }
    }

'''
    st = must_replace(st, "    private fun setupThemeSelector() {", func + "    private fun setupThemeSelector() {", "setupThemeSelector")
    write(KT_SETTINGS, st)
    print("[ok] SettingsActivity: switches de cookies")

# ---------------------------------------------------------------------------
# 5) AdBlockManager: lista de permissao
# ---------------------------------------------------------------------------
ab = read(KT_ADBLOCK)
if MARK in ab:
    print("[skip] AdBlockManager ja com allowlist")
else:
    ab = must_replace(
        ab,
        "    private const val DECISION_CACHE_MAX = 4000\n",
        "    private const val DECISION_CACHE_MAX = 4000\n\n"
        "    // " + MARK + ": hosts de login/desafio que NUNCA podem ser bloqueados\n"
        "    // (casam o host exato e qualquer subdominio). Bloquear qualquer um\n"
        "    // deles deixa /gsi/transform em branco ou o Turnstile em loop.\n"
        "    private val ALLOWLIST_HOSTS = listOf(\n"
        "        \"accounts.google.com\",\n"
        "        \"accounts.youtube.com\",\n"
        "        \"apis.google.com\",\n"
        "        \"ssl.gstatic.com\",\n"
        "        \"www.gstatic.com\",\n"
        "        \"challenges.cloudflare.com\",\n"
        "        \"recaptcha.net\"\n"
        "    )\n",
        "constantes adblock",
    )
    ab = must_replace(
        ab,
        "        } ?: return false\n\n        val blocked = decisionCache.getOrPut(host)",
        "        } ?: return false\n\n"
        "        if (isAllowListed(host, url)) return false\n\n"
        "        val blocked = decisionCache.getOrPut(host)",
        "shouldBlock",
    )
    func = '''    /** ''' + MARK + ''': true se o recurso pertence a um fluxo de login/desafio. */
    private fun isAllowListed(host: String, url: String): Boolean {
        for (allowed in ALLOWLIST_HOSTS) {
            if (host == allowed || host.endsWith(".$allowed")) return true
        }
        if (host == "www.google.com" || host == "google.com") {
            val path = try {
                Uri.parse(url).path ?: ""
            } catch (e: Exception) {
                ""
            }
            if (path.startsWith("/recaptcha/") || path.startsWith("/gsi/")) return true
        }
        return false
    }

'''
    ab = must_replace(ab, "    private fun loadDomains(context: Context)", func + "    private fun loadDomains(context: Context)", "loadDomains")
    write(KT_ADBLOCK, ab)
    print("[ok] AdBlockManager: allowlist de login")

# ---------------------------------------------------------------------------
# 6) MainActivity
# ---------------------------------------------------------------------------
k = read(KT_MAIN)
if MARK in k:
    print("[skip] MainActivity ja corrigida")
else:
    # 6.1 imports
    k = must_replace(
        k,
        "import com.xaulinxs.funcoes.AdBlockManager\n",
        "import com.xaulinxs.funcoes.AdBlockManager\nimport com.xaulinxs.funcoes.CookiePrefsManager\n",
        "import CookiePrefsManager",
    )
    k = must_replace(
        k,
        "import android.os.Looper\n",
        "import android.os.Looper\nimport android.os.Message\n",
        "import Message",
    )

    # 6.2 campos do popup
    k = must_replace(
        k,
        "    private var webView: WebView? = null\n",
        "    private var webView: WebView? = null\n\n"
        "    // " + MARK + ": popup real de window.open() (login Google/GSI etc.)\n"
        "    private var popupDialog: android.app.Dialog? = null\n"
        "    private var popupWebView: WebView? = null\n",
        "campos popup",
    )

    # 6.3 settings
    cookie_line = "        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(newWebView, true)\n"
    if cookie_line in k:
        # veio do script anterior (fixo em true) -> passa a obedecer o toggle
        k = k.replace(cookie_line, "", 1)
    k = must_replace(
        k,
        "        newWebView.settings.allowContentAccess = true\n",
        "        newWebView.settings.allowContentAccess = true\n"
        "        // " + MARK + ": GSI abre o login via window.open() - sem\n"
        "        // multiplas janelas ele renderiza no frame errado (tela branca em\n"
        "        // /gsi/transform). databaseEnabled/dom storage ajudam o Turnstile.\n"
        "        newWebView.settings.databaseEnabled = true\n"
        "        newWebView.settings.setSupportMultipleWindows(true)\n"
        "        newWebView.settings.javaScriptCanOpenWindowsAutomatically = true\n"
        "        CookiePrefsManager.apply(this, newWebView)\n",
        "settings",
    )

    # 6.4 WebChromeClient: onCreateWindow / onCloseWindow
    chrome = '''            // ''' + MARK + ''' ---- janelas (window.open / target=_blank) ----
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                if (view == null || resultMsg == null) return false
                val hit = view.hitTestResult.type
                val isLink = hit == WebView.HitTestResult.SRC_ANCHOR_TYPE ||
                    hit == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
                // Mini bloqueador de pop-up: janela sem gesto do usuario e que
                // nao veio de um link e recusada.
                if (!isUserGesture && !isLink) return false
                return if (isLink) openLinkInCurrentView(resultMsg) else openPopupWindow(resultMsg)
            }

            override fun onCloseWindow(window: WebView?) {
                if (window != null && window === popupWebView) closePopupWindow()
            }

'''
    k = must_replace(
        k,
        "            override fun onShowFileChooser(\n",
        chrome + "            override fun onShowFileChooser(\n",
        "onShowFileChooser",
    )

    # 6.5 funcoes novas
    funcs = r'''    // XAULINXS_FIX_V3_LOGIN
    /**
     * Link com target="_blank": em vez de criar uma janela, descobre a URL
     * pela WebView temporária (transport) e carrega na WebView atual.
     */
    private fun openLinkInCurrentView(resultMsg: Message): Boolean {
        val temp = WebView(this)
        var handled = false
        fun redirect(url: String?) {
            if (handled || url.isNullOrBlank() || url == "about:blank") return
            handled = true
            webView?.loadUrl(url)
            mediaMenuHandler.post { temp.destroy() }
        }
        temp.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                redirect(request?.url?.toString())
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                redirect(url)
            }
        }
        val transport = resultMsg.obj as WebView.WebViewTransport
        transport.webView = temp
        resultMsg.sendToTarget()
        // Se nada navegou (janela vazia), não deixa a WebView temporária vazando.
        mediaMenuHandler.postDelayed({ if (!handled) temp.destroy() }, 10000)
        return true
    }

    /**
     * window.open() de verdade (ex.: login do Google/GSI): abre um Dialog
     * com uma WebView própria e devolve ela como destino da janela. Assim
     * window.opener e postMessage continuam funcionando, que é o que o
     * GSI usa para devolver o token para a página original. A janela fecha
     * sozinha quando o site chama window.close() (onCloseWindow).
     */
    private fun openPopupWindow(resultMsg: Message): Boolean {
        closePopupWindow()

        val popup = WebView(this)
        val ps = popup.settings
        ps.javaScriptEnabled = true
        ps.domStorageEnabled = true
        ps.databaseEnabled = true
        ps.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        ps.setSupportMultipleWindows(false)
        ps.javaScriptCanOpenWindowsAutomatically = true
        // Mesmo User-Agent da WebView principal durante toda a sessão.
        webView?.settings?.userAgentString?.let { ps.userAgentString = it }
        CookiePrefsManager.apply(this, popup)
        popup.webViewClient = object : WebViewClient() {}

        val density = resources.displayMetrics.density
        val pad = (12 * density).toInt()

        val title = TextView(this)
        title.textSize = 14f
        title.maxLines = 1
        title.ellipsize = android.text.TextUtils.TruncateAt.END
        title.setPadding(pad, pad, pad, pad)

        val close = TextView(this)
        close.text = "\u2715"
        close.textSize = 20f
        close.setPadding(pad * 2, pad, pad * 2, pad)
        close.setOnClickListener { closePopupWindow() }

        popup.webChromeClient = object : WebChromeClient() {
            override fun onCloseWindow(window: WebView?) {
                closePopupWindow()
            }

            override fun onReceivedTitle(view: WebView?, t: String?) {
                title.text = t ?: ""
            }
        }

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(close)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.addView(bar)
        root.addView(popup, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val dialog = android.app.Dialog(this, android.R.style.Theme_DeviceDefault_Light_NoActionBar)
        dialog.setContentView(root)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) {
                    if (popup.canGoBack()) popup.goBack() else closePopupWindow()
                }
                true
            } else {
                false
            }
        }
        dialog.window?.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        )

        popupWebView = popup
        popupDialog = dialog

        val transport = resultMsg.obj as WebView.WebViewTransport
        transport.webView = popup
        resultMsg.sendToTarget()
        dialog.show()
        return true
    }

    private fun closePopupWindow() {
        val dialog = popupDialog
        val popup = popupWebView
        popupDialog = null
        popupWebView = null
        try {
            dialog?.dismiss()
        } catch (e: Exception) {
            // Activity já finalizando - nada a fazer.
        }
        if (popup != null) {
            popup.stopLoading()
            (popup.parent as? android.view.ViewGroup)?.removeView(popup)
            popup.destroy()
        }
    }

    override fun onResume() {
        super.onResume()
        // O toggle de cookies de terceiros vive nas Configurações (outra
        // Activity): reaplica na WebView ao voltar.
        CookiePrefsManager.apply(this, webView)
    }

    override fun onPause() {
        // Grava os cookies em disco - mantém o login se o processo morrer.
        android.webkit.CookieManager.getInstance().flush()
        super.onPause()
    }

'''
    k = must_replace(k, "    override fun onDestroy() {\n", funcs + "    override fun onDestroy() {\n        closePopupWindow() // " + MARK + "\n", "onDestroy")

    write(KT_MAIN, k)
    print("[ok] MainActivity corrigida")

print("\nPronto. Compile com: ./gradlew assembleDebug")
