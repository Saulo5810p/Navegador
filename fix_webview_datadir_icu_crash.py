#!/usr/bin/env python3
"""
Corrige o crash nativo (SIGTRAP / "Invalid file descriptor to ICU data
received") do processo renderer sandbox do WebView AOSP embutido.

Causa raiz: em AbstractWebViewPackageManagerHook.fillPackageInfo(), o
ApplicationInfo falso montado para o pacote "com.android.webview" embutido
define sourceDir/publicSourceDir/nativeLibraryDir, mas nunca define
dataDir (nem credentialProtectedDataDir/deviceProtectedDataDir). Isso faz
Context#createPackageContext() estourar NullPointerException dentro do
framework (LoadedApk.canAccessDataDir() -> new File(null)), quando chamado
por SandboxedProcessServiceDelegate.tryCreateWebViewPackageContext() no
processo renderer sandbox.

Sem esse createPackageContext, o delegate cai no caminho de fallback
degradado ("merge total de dex", já visto nos logs anteriores como
"无 WebView 包 Context，使用全量 dex 合并"), que não tem o mesmo cuidado com
o AssetManager que o caminho principal (ChromiumDelegatingClassLoader) tem
- e é exatamente esse caminho principal que a própria lib documenta como
"avoid ICU FD invalido". Sem ele, o Chromium não acha assets/icudtl.dat e
o processo renderer aborta com SIGTRAP assim que tenta inicializar o ICU.

Correcao: como esse "pacote" fake roda sob o mesmo processo/uid do app
host, reaproveitamos o dataDir real do host (sempre existe e e acessivel)
para preencher dataDir/credentialProtectedDataDir/deviceProtectedDataDir
do ApplicationInfo falso.

Uso (rodar dentro da pasta local do repo clonado no Termux):
    cd Browser
    python fix_webview_datadir_icu_crash.py
"""

import sys
from pathlib import Path

TARGET = Path(
    "app/src/main/java/com/norman/webviewup/lib/hook/"
    "AbstractWebViewPackageManagerHook.java"
)

OLD_BLOCK = """        packageInfo.applicationInfo.sourceDir = apkPath;
        packageInfo.applicationInfo.publicSourceDir = apkPath;
        packageInfo.applicationInfo.flags |= ApplicationInfo.FLAG_INSTALLED
                | ApplicationInfo.FLAG_HAS_CODE;

        cachedWebViewAppInfo = packageInfo.applicationInfo;"""

NEW_BLOCK = """        packageInfo.applicationInfo.sourceDir = apkPath;
        packageInfo.applicationInfo.publicSourceDir = apkPath;
        packageInfo.applicationInfo.flags |= ApplicationInfo.FLAG_INSTALLED
                | ApplicationInfo.FLAG_HAS_CODE;
        // dataDir nulo faz LoadedApk.canAccessDataDir() (chamada por
        // createPackageContext, usada pelo SandboxedProcessServiceDelegate no
        // processo renderer sandbox) estourar NullPointerException em
        // "new File(mApplicationInfo.dataDir)". Sem createPackageContext bem
        // sucedido, o delegate cai no fallback degradado que nao consegue
        // achar assets/icudtl.dat, e o renderer trava com SIGTRAP
        // ("Invalid file descriptor to ICU data received"). Como esse
        // "pacote" fake roda sob o mesmo processo/uid do app host,
        // reaproveitamos o dataDir real do host, que sempre existe e e
        // acessivel.
        String hostDataDir = context.getApplicationInfo().dataDir;
        if (!TextUtils.isEmpty(hostDataDir)) {
            packageInfo.applicationInfo.dataDir = hostDataDir;
            packageInfo.applicationInfo.deviceProtectedDataDir = hostDataDir;
        }

        cachedWebViewAppInfo = packageInfo.applicationInfo;"""

MARKER = "String hostDataDir = context.getApplicationInfo().dataDir;"


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

    if MARKER in original:
        print("Nada a fazer: AbstractWebViewPackageManagerHook.java ja esta corrigido (idempotente).")
        return

    if OLD_BLOCK not in original:
        fail(
            "Nao encontrei o trecho esperado de fillPackageInfo() em "
            "AbstractWebViewPackageManagerHook.java - o arquivo pode ter "
            "mudado. Corrija manualmente."
        )

    text = original.replace(OLD_BLOCK, NEW_BLOCK, 1)

    TARGET.write_text(text, encoding="utf-8")
    print(f"OK: {TARGET} corrigido.")
    print("Agora e so recompilar:  ./gradlew assembleDebug")


if __name__ == "__main__":
    main()
