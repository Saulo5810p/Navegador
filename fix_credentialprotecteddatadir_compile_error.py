#!/usr/bin/env python3
"""
Corrige o erro de compilacao:
  "cannot find symbol: variable credentialProtectedDataDir"

introduzido pelo fix_webview_datadir_icu_crash.py anterior. O campo
`credentialProtectedDataDir` de ApplicationInfo e interno do framework
(@UnsupportedAppUsage) e nao esta disponivel via API publica - so
`dataDir` e `deviceProtectedDataDir` sao publicos (ambos desde a API 24).
Este script remove a linha invalida, mantendo o resto da correcao (que
ja resolve o crash de ICU sozinha).

Uso (rodar dentro da pasta local do repo clonado no Termux):
    cd Navegador
    python fix_credentialprotecteddatadir_compile_error.py
    ./gradlew assembleDebug
"""

import sys
from pathlib import Path

TARGET = Path(
    "app/src/main/java/com/norman/webviewup/lib/hook/"
    "AbstractWebViewPackageManagerHook.java"
)

BAD_LINE = "            packageInfo.applicationInfo.credentialProtectedDataDir = hostDataDir;\n"


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

    if BAD_LINE not in original:
        if "hostDataDir" in original:
            print("Nada a fazer: a linha invalida ja nao existe (idempotente).")
            return
        fail(
            "Nao encontrei a linha esperada nem o marcador 'hostDataDir' em "
            "AbstractWebViewPackageManagerHook.java. Rode primeiro o "
            "fix_webview_datadir_icu_crash.py, ou o arquivo mudou - "
            "corrija manualmente."
        )

    text = original.replace(BAD_LINE, "", 1)

    TARGET.write_text(text, encoding="utf-8")
    print(f"OK: {TARGET} corrigido.")
    print("Agora e so recompilar:  ./gradlew assembleDebug")


if __name__ == "__main__":
    main()
