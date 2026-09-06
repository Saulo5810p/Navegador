#!/usr/bin/env python3
"""
Corrige o FATAL EXCEPTION: android.os.FileUriExposedException que ocorre
ao clicar/abrir a notificacao de "download concluido".

Causa: DownloadManager.COLUMN_LOCAL_URI retorna um URI file:///storage/...
e o app estava usando Uri.parse(localUri) direto num Intent.ACTION_VIEW
guardado num PendingIntent. Desde o Android 7 (API 24), expor um file://
a outro processo (mesmo dentro de uma notificacao) e proibido pelo
StrictMode e derruba o app.

Correcao: converte o file:// para content:// via FileProvider (o mesmo
authority "${applicationId}.fileprovider" e file_paths.xml que ja existem
no projeto, usados pelo FileManagerActivity) antes de montar o Intent.

Rode este script na raiz do projeto Navegador:
    python fix_fileuri_download_crash.py
"""
import sys
from pathlib import Path

TARGET = Path("app/src/main/java/com/xaulinxs/funcoes/download/DownloadForegroundService.kt")

OLD_IMPORTS = """import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.xaulinxs.aosp.browser.R"""

NEW_IMPORTS = """import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.xaulinxs.aosp.browser.R
import java.io.File"""

OLD_METHOD = '''    private fun buildFinishedNotification(fileName: String, success: Boolean, localUri: String?): Notification {
        val openIntent = if (success && localUri != null) {
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(localUri), "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else null'''

NEW_METHOD = '''    /**
     * COLUMN_LOCAL_URI do DownloadManager costuma vir como file:// (ex:
     * "file:///storage/emulated/0/Download/arquivo.apk"). Desde o Android 7
     * (API 24), expor um file:// em um Intent para outro app/processo lanca
     * FileUriExposedException (StrictMode) - inclusive dentro de um
     * PendingIntent guardado numa notificacao, que so "explode" quando o
     * usuario toca nela. E obrigatorio converter para content:// via
     * FileProvider (ja declarado no manifest com o mesmo authority/paths
     * usados pelo FileManagerActivity) antes de montar o Intent.
     */
    private fun resolveShareableUri(localUri: String): Uri? {
        val parsed = Uri.parse(localUri)
        return if (parsed.scheme == "file") {
            val file = parsed.path?.let { File(it) } ?: return null
            try {
                FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            } catch (e: IllegalArgumentException) {
                // Caminho fora dos <paths> declarados em file_paths.xml -
                // nao ha como gerar content:// para ele; melhor nao abrir
                // nada do que crashar de novo.
                null
            }
        } else {
            // Ja e content:// (ou outro esquema seguro) - usa como esta.
            parsed
        }
    }

    private fun buildFinishedNotification(fileName: String, success: Boolean, localUri: String?): Notification {
        val shareableUri = localUri?.let { resolveShareableUri(it) }
        val openIntent = if (success && shareableUri != null) {
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(shareableUri, "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else null'''


def main():
    if not TARGET.exists():
        print(f"ERRO: nao encontrei {TARGET}")
        print("Rode este script na raiz do projeto Navegador (onde tem a pasta app/).")
        sys.exit(1)

    content = TARGET.read_text(encoding="utf-8")

    if "FileProvider.getUriForFile" in content:
        print("Ja aplicado antes - nada a fazer.")
        sys.exit(0)

    if OLD_IMPORTS not in content:
        print("ERRO: bloco de imports esperado nao encontrado (arquivo pode ja ter sido modificado).")
        sys.exit(1)
    if OLD_METHOD not in content:
        print("ERRO: metodo buildFinishedNotification esperado nao encontrado (arquivo pode ja ter sido modificado).")
        sys.exit(1)

    content = content.replace(OLD_IMPORTS, NEW_IMPORTS)
    content = content.replace(OLD_METHOD, NEW_METHOD)

    TARGET.write_text(content, encoding="utf-8")
    print(f"OK: {TARGET} corrigido.")
    print("Agora e so recompilar e testar um download.")


if __name__ == "__main__":
    main()
