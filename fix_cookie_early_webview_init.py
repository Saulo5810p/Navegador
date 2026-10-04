#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
fix_cookie_early_webview_init.py   (marcador: XAULINXS_COOKIE_EARLY_V1)

Uso (na raiz do repo Navegador, no Termux):
    python3 fix_cookie_early_webview_init.py
ou: python3 fix_cookie_early_webview_init.py /caminho/do/Navegador

Problema (visto no logcat do botao "Recarregar WebView")
--------------------------------------------------------
    11:49:18.027  WebViewFactory: Loading com.google.android.webview 126.0.6478.110
    11:49:21.454  WebViewReplaceException: WebView can only be replaced
                  before System WebView init

Quando o aosp_webview.apk precisa ser copiado de novo (apk novo, botao de
recarregar, primeira instalacao), a copia roda em outra thread (~3 s) e o
upgrade so acontece depois. Nesse intervalo a MainActivity ja chegou em
onResume(), que chamava CookiePrefsManager.apply(this, webView) mesmo com
webView == null; apply() chama CookieManager.getInstance(), e isso carrega o
WebView DO SISTEMA nesse processo. Como o provider so e resolvido uma vez por
processo, o upgrade chegava tarde e caia em onUpgradeError.
Quando o apk ja estava copiado, o upgrade rodava na hora, dentro de
Application.onCreate, antes de qualquer Activity - por isso so quebrava logo
depois de trocar o apk.
O mesmo vale para onPause() (CookieManager.getInstance().flush()) e para o
Switch de cookies nas Configuracoes (setCookiesEnabled).

Correcao
--------
CookiePrefsManager so toca no CookieManager depois que o upgrade terminou
(WebViewUpgrade.isCompleted() || isFailed()). As preferencias continuam
sendo gravadas; sao aplicadas quando a WebView e criada (initWebView ja chama
CookiePrefsManager.apply). MainActivity.onPause passa a usar
CookiePrefsManager.flush() com a mesma protecao.

Idempotente: rodar duas vezes nao duplica nada.
"""
import os
import sys

MARK = "XAULINXS_COOKIE_EARLY_V1"

ROOT = sys.argv[1] if len(sys.argv) > 1 else os.getcwd()
JAVA = os.path.join(ROOT, "app", "src", "main", "java", "com", "xaulinxs")
COOKIE = os.path.join(JAVA, "funcoes", "CookiePrefsManager.kt")
MAIN = os.path.join(JAVA, "aosp", "browser", "MainActivity.kt")


def read(path):
    with open(path, "r", encoding="utf-8") as f:
        return f.read()


def write(path, text):
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


def need(path):
    if not os.path.isfile(path):
        sys.exit("ERRO: arquivo nao encontrado: %s\n(rode na raiz do repo Navegador)" % path)


def replace_once(text, old, new, what):
    if old not in text:
        sys.exit("ERRO: trecho nao encontrado (%s) - o arquivo mudou?" % what)
    return text.replace(old, new, 1)


def patch_cookie_manager():
    need(COOKIE)
    s = read(COOKIE)
    if MARK in s:
        print("= CookiePrefsManager.kt ja atualizado")
        return

    s = replace_once(
        s,
        "import android.webkit.WebView\n",
        "import android.webkit.WebView\nimport com.norman.webviewup.lib.WebViewUpgrade\n",
        "import do WebView",
    )

    s = replace_once(
        s,
        """        prefs(context).edit().putBoolean(KEY_COOKIES, enabled).apply()
        CookieManager.getInstance().setAcceptCookie(enabled)
    }""",
        """        prefs(context).edit().putBoolean(KEY_COOKIES, enabled).apply()
        // XAULINXS_COOKIE_EARLY_V1: a preferencia ja foi gravada; se o upgrade
        // ainda nao terminou, apply() aplica quando a WebView for criada.
        if (providerReady()) {
            CookieManager.getInstance().setAcceptCookie(enabled)
        }
    }""",
        "setCookiesEnabled",
    )

    s = replace_once(
        s,
        """    fun apply(context: Context, webView: WebView?) {
        val manager = CookieManager.getInstance()""",
        """    fun apply(context: Context, webView: WebView?) {
        // XAULINXS_COOKIE_EARLY_V1: CookieManager.getInstance() carrega o provider
        // do WebView. Antes do upgrade terminar isso carregaria o WebView DO
        // SISTEMA e o upgrade falharia ("can only be replaced before System
        // WebView init"). initWebView() chama apply() de novo ja com o provider
        // certo, entao pular aqui nao perde nada.
        if (!providerReady()) return
        val manager = CookieManager.getInstance()""",
        "apply",
    )

    s = replace_once(
        s,
        """    private fun prefs(context: Context) =""",
        """    /** Grava os cookies em disco - sem tocar no provider antes do upgrade terminar. */
    fun flush() {
        if (!providerReady()) return
        try {
            CookieManager.getInstance().flush()
        } catch (e: Exception) {
            // sem provider utilizavel: nada para gravar
        }
    }

    /** true quando o WebViewUpgrade ja terminou (com sucesso ou com falha) - o provider final ja esta decidido. */
    private fun providerReady(): Boolean =
        WebViewUpgrade.isCompleted() || WebViewUpgrade.isFailed()

    private fun prefs(context: Context) =""",
        "prefs()",
    )
    write(COOKIE, s)
    print("+ CookiePrefsManager.kt atualizado")


def patch_main():
    need(MAIN)
    s = read(MAIN)
    if MARK in s:
        print("= MainActivity.kt ja atualizado")
        return
    s = replace_once(
        s,
        """        // Grava os cookies em disco - mantém o login se o processo morrer.
        android.webkit.CookieManager.getInstance().flush()
        super.onPause()""",
        """        // Grava os cookies em disco - mantém o login se o processo morrer.
        // XAULINXS_COOKIE_EARLY_V1: via CookiePrefsManager, que não toca no
        // CookieManager (e portanto no WebView do sistema) antes do upgrade.
        CookiePrefsManager.flush()
        super.onPause()""",
        "onPause",
    )
    write(MAIN, s)
    print("+ MainActivity.kt atualizado")


def main():
    need(COOKIE)
    need(MAIN)
    patch_cookie_manager()
    patch_main()
    print("\nPronto. Agora: ./gradlew assembleDebug")


if __name__ == "__main__":
    main()
