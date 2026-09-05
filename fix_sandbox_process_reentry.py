#!/usr/bin/env python3
"""
Corrige o crash pos-carregamento do WebView AOSP no projeto Browser.

Causa raiz: BrowserApplication.onCreate() chama WebViewUpgrade.upgrade()
incondicionalmente. Application.onCreate() roda de novo em cada processo
sandbox (":sandboxed_process0" etc.) que a propria lib WebViewUpgrade cria
durante a etapa de verificacao (checkWebView), o que reinicia toda a troca
de WebView (copia do apk, hooks de ActivityManager/PackageManager,
checkWebView de novo) DENTRO desses processos sandbox - reentrancia
recursiva que deixa o provider em estado inconsistente bem na hora em que
a MainActivity real tenta criar sua WebView de verdade.

Correcao: usar o utilitario ProcessUtils.isMainProcess(context), que ja
existe na lib vendorizada (com.norman.webviewup.lib.util.ProcessUtils),
para so rodar WebViewUpgrade.upgrade() no processo principal do app.

Uso (rodar dentro da pasta local do repo clonado no Termux):
    cd Browser
    python fix_sandbox_process_reentry.py
"""

import re
import sys
from pathlib import Path

TARGET = Path("app/src/main/java/com/xaulinxs/aosp/browser/BrowserApplication.kt")

IMPORT_LINE = "import com.norman.webviewup.lib.util.ProcessUtils"

GUARD_SNIPPET = """        // WebViewUpgrade.upgrade() so pode rodar no processo principal.
        // Application.onCreate() roda de novo em cada processo sandbox
        // (":sandboxed_process0" etc.) que a propria lib cria durante a
        // verificacao (checkWebView) - sem essa guarda, o upgrade reentra
        // recursivamente nesses processos e deixa o provider inconsistente
        // bem na hora em que a MainActivity real cria sua WebView.
        if (!ProcessUtils.isMainProcess(this)) {
            return
        }
"""


def fail(msg: str) -> None:
    print(f"ERRO: {msg}", file=sys.stderr)
    sys.exit(1)


def main() -> None:
    if not TARGET.exists():
        fail(
            f"Nao encontrei {TARGET}. Rode este script na raiz do repo "
            "(a pasta que contem 'app/')."
        )

    original = TARGET.read_text(encoding="utf-8")
    text = original

    already_imported = IMPORT_LINE in text
    already_guarded = "ProcessUtils.isMainProcess(this)" in text

    if already_imported and already_guarded:
        print("Nada a fazer: BrowserApplication.kt ja esta corrigido (idempotente).")
        return

    if not already_imported:
        marker = "import com.norman.webviewup.lib.WebViewUpgrade"
        if marker not in text:
            fail(
                "Nao encontrei o import de WebViewUpgrade em BrowserApplication.kt "
                "- o arquivo pode ter mudado. Corrija manualmente."
            )
        text = text.replace(marker, f"{marker}\n{IMPORT_LINE}", 1)

    if not already_guarded:
        m = re.search(r"override fun onCreate\(\)\s*\{\s*\n\s*super\.onCreate\(\)\n", text)
        if not m:
            fail(
                "Nao encontrei o inicio de onCreate() em BrowserApplication.kt "
                "- o arquivo pode ter mudado. Corrija manualmente."
            )
        insert_at = m.end()
        text = text[:insert_at] + GUARD_SNIPPET + text[insert_at:]

    if text == original:
        print("Nada a fazer: BrowserApplication.kt ja esta corrigido (idempotente).")
        return

    TARGET.write_text(text, encoding="utf-8")
    print(f"OK: {TARGET} corrigido.")
    print("Agora e so recompilar:  ./gradlew assembleDebug")


if __name__ == "__main__":
    main()
