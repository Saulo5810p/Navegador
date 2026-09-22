#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
corrigir_login_google.py

Corrige o bloqueio de login com conta Google (e outros logins via OAuth,
como Facebook) dentro do WebView do projeto "Navegador".

DIAGNÓSTICO REAL (confirmado, não é o problema de "cache/CDN" que blogs
de terceiros costumam sugerir):

Desde 2016, e de forma bem mais rígida desde 24/jul/2023, o Google
BLOQUEIA no servidor qualquer tela de login que se identifique como
WebView, retornando o erro "403: disallowed_useragent". A identificação
é feita, entre outras coisas, pelos tokens "; wv" e "Version/4.0" que o
Android WebView (mas não o Chrome de verdade) inclui automaticamente no
cabeçalho User-Agent de toda requisição.

O código do app (MainActivity.kt, dentro de initWebView()) nunca definia
um User-Agent próprio - então o WebView saía com o User-Agent padrão,
que contém "; wv", e o Google barrava o login antes mesmo da página
carregar.

Como este projeto É o navegador em si (não é um WebView escondido dentro
de outro app tentando roubar sessão do usuário, que é o que a política
do Google mira), a correção correta aqui NÃO é trocar para Android
Custom Tabs (isso descaracterizaria o projeto - Custom Tabs abre a
instância do navegador de sistema, não o WebView do próprio app). A
correção é fazer o WebView se identificar como um navegador de verdade,
removendo só os tokens que entregam que é um WebView, sem inventar
versão de Android/Chromium nenhuma - o resto do User-Agent continua
vindo do próprio dispositivo.

Também garante que cookies (inclusive de terceiros, usados em alguns
fluxos de conta Google) fiquem habilitados, já que sem isso a sessão de
login pode não persistir mesmo com o User-Agent corrigido.

Como usar:
    1. Copie este arquivo para dentro da pasta do projeto (Navegador/).
    2. Rode:  python3 corrigir_login_google.py
    3. Rode:  ./gradlew assembleDebug
    4. Instale o APK e teste o login de novo.

Seguro rodar mais de uma vez: se a correção já foi aplicada, o script
avisa e não mexe em nada. Um backup do arquivo original é salvo com
extensão .bak na primeira execução.
"""

import sys
from pathlib import Path

ALVO = Path("app/src/main/java/com/xaulinxs/aosp/browser/MainActivity.kt")

IMPORT_ANCORA = "import android.webkit.ValueCallback\n"
IMPORTS_NOVOS = (
    "import android.webkit.CookieManager\n"
    "import android.webkit.ValueCallback\n"
)

ANCORA_CODIGO = "        newWebView.settings.allowContentAccess = true\n"

CODIGO_NOVO = (
    ANCORA_CODIGO
    + "\n"
    + "        // O Google (e outros provedores OAuth) bloqueia login vindo de\n"
    + "        // qualquer WebView que se identifique como WebView: desde 2016, e de\n"
    + "        // forma rigida desde jul/2023, requisicoes com \"; wv\" ou \"Version/4.0\"\n"
    + "        // no User-Agent levam ao erro \"403: disallowed_useragent\". Este app E\n"
    + "        // o navegador (nao um WebView escondido dentro de outro app), entao o\n"
    + "        // certo aqui e o WebView se identificar como um navegador de verdade -\n"
    + "        // so removendo esses tokens, sem inventar versao de Android/Chromium.\n"
    + "        val userAgentDeNavegador = android.webkit.WebSettings.getDefaultUserAgent(this)\n"
    + "            .replace(\"; wv\", \"\")\n"
    + "            .replace(\"Version/4.0 \", \"\")\n"
    + "        newWebView.settings.userAgentString = userAgentDeNavegador\n"
    + "\n"
    + "        // Login do Google depende de cookies persistirem (inclusive de\n"
    + "        // terceiros, em alguns fluxos de conta) - sem isso, mesmo com o\n"
    + "        // User-Agent corrigido, a sessao pode nao se manter.\n"
    + "        val cookieManager = CookieManager.getInstance()\n"
    + "        cookieManager.setAcceptCookie(true)\n"
    + "        cookieManager.setAcceptThirdPartyCookies(newWebView, true)\n"
)


def falhar(msg):
    print("\n[ERRO] " + msg)
    print(
        "\nNão consegui aplicar a correção automaticamente porque o arquivo "
        "está diferente do que eu esperava (talvez já tenha sido editado, ou "
        "o script foi rodado na pasta errada)."
    )
    print(f"Confirme que você está rodando este script dentro da pasta 'Navegador' "
          f"e que o arquivo existe em: {ALVO}")
    sys.exit(1)


def main():
    if not ALVO.exists():
        falhar(f"Não encontrei o arquivo {ALVO}.")

    texto = ALVO.read_text(encoding="utf-8")

    ja_corrigido = "userAgentDeNavegador" in texto

    if ja_corrigido:
        print("[OK] Esta correção já foi aplicada. Nada a fazer.")
        print("Pode rodar: ./gradlew assembleDebug")
        return

    if ANCORA_CODIGO not in texto:
        falhar("Não encontrei o ponto certo (initWebView) para inserir a correção.")

    if IMPORT_ANCORA not in texto:
        falhar("Não encontrei onde adicionar o import de CookieManager.")

    backup = ALVO.with_suffix(ALVO.suffix + ".bak")
    if not backup.exists():
        backup.write_text(texto, encoding="utf-8")
        print(f"[OK] Backup salvo em: {backup}")

    novo_texto = texto.replace(IMPORT_ANCORA, IMPORTS_NOVOS, 1)
    novo_texto = novo_texto.replace(ANCORA_CODIGO, CODIGO_NOVO, 1)

    ALVO.write_text(novo_texto, encoding="utf-8")

    print(f"[OK] Corrigido: {ALVO}")
    print(
        "\nO que foi mudado, dentro de initWebView():\n"
        "  - O WebView passa a usar um User-Agent sem os tokens '; wv' e\n"
        "    'Version/4.0', que são o que o Google usa pra detectar e bloquear\n"
        "    login (erro 403: disallowed_useragent). O resto do User-Agent\n"
        "    (Android, dispositivo, versão do Chromium) continua real.\n"
        "  - Cookies (inclusive de terceiros) ficam explicitamente habilitados,\n"
        "    pra sessão de login persistir.\n"
    )
    print(
        "IMPORTANTE: essa correção resolve o bloqueio de servidor baseado em\n"
        "User-Agent. Se depois de instalar o APK novo o login AINDA falhar,\n"
        "conecte o celular no PC e abra chrome://inspect no Chrome desktop pra\n"
        "ver o console da página de login e me mandar a mensagem de erro exata\n"
        "- pode ser um bloqueio diferente (ex: por client hints, ou por causa de\n"
        "algum addJavascriptInterface exposto)."
    )
    print("\nAgora rode:  ./gradlew assembleDebug")


if __name__ == "__main__":
    main()
