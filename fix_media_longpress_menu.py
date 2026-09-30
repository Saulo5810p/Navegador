#!/usr/bin/env python3
"""
Adiciona a feature de menu compacto por pressionar-e-segurar em cima de
uma imagem ou vídeo dentro da WebView do projeto Navegador:

    - Baixar imagem/vídeo (via BrowserDownloadManager, se o servidor
      permitir download direto do link — não funciona para blob:/canvas
      gerado só em JS sem URL real).
    - Copiar link (ClipboardManager, mesmo padrão do histórico).
    - Abrir em novo WebView (destrói a instância atual e cria uma nova,
      já carregando a URL da mídia).

Detecção de mídia: NÃO depende só de WebView.getHitTestResult() (a API
pública não tem um tipo "VIDEO" — só ANCHOR/IMAGE/SRC_IMAGE_ANCHOR/etc,
então <video> nunca seria pego). Em vez disso, guarda a última posição
de toque (ACTION_DOWN) e, no long-press, injeta um JavaScript que faz
document.elementFromPoint() nessa posição, sobe pela árvore de pais
procurando <img>, <video>/<source> ou um elemento com background-image,
e devolve o resultado via um WebAppInterface (add​JavascriptInterface).
Isso cobre tanto <img> quanto <video> com a mesma lógica.

Como o long-press precisa decidir de forma síncrona se vai consumir o
evento (suprimindo o menu nativo de seleção de texto do WebView), o
listener sempre retorna true e injeta o JS; se nenhuma mídia for
encontrada no ponto tocado, nada aparece (o toque é simplesmente
ignorado) — troca consciente, documentada no tutorial.

Idempotente: pode rodar mais de uma vez sem duplicar nada.
"""

import re
import sys
from pathlib import Path

REPO_ROOT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(".")
MAIN_ACTIVITY = REPO_ROOT / "app/src/main/java/com/xaulinxs/aosp/browser/MainActivity.kt"
STRINGS_XML = REPO_ROOT / "app/src/main/res/values/strings.xml"

MARKER = "XAULINXS_MEDIA_LONGPRESS_MENU"


def patch_strings():
    text = STRINGS_XML.read_text(encoding="utf-8")
    if MARKER in text:
        print("strings.xml: já aplicado, pulando.")
        return
    block = f"""
    <!-- {MARKER} -->
    <string name="media_menu_download_image">Baixar imagem</string>
    <string name="media_menu_download_video">Baixar vídeo</string>
    <string name="media_menu_copy_link">Copiar link</string>
    <string name="media_menu_new_webview">Abrir em novo WebView</string>
    <string name="media_menu_download_failed">Não foi possível baixar direto deste site</string>
    <string name="media_menu_link_copied">Link copiado</string>
"""
    text = text.replace("</resources>", block + "</resources>")
    STRINGS_XML.write_text(text, encoding="utf-8")
    print("strings.xml: strings do menu de mídia adicionadas.")


IMPORTS_TO_ADD = [
    "import android.content.ClipData",
    "import android.content.ClipboardManager",
    "import android.webkit.JavascriptInterface",
    "import android.os.Handler",
    "import android.os.Looper",
]

FIELDS_BLOCK = f"""
    // {MARKER}: última posição tocada na WebView (ACTION_DOWN), em
    // pixels de tela - convertida pra CSS px (dividindo por
    // webView.scale) na hora de montar o JS de detecção. Usada pelo
    // long-press pra saber ONDE checar se existe imagem/vídeo.
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    // Handler pra rodar no thread principal o callback do
    // JavascriptInterface (evaluateJavascript/addJavascriptInterface
    // chamam de uma thread de WebCore, nunca da UI thread).
    private val mediaMenuHandler = Handler(Looper.getMainLooper())
"""

TOUCH_AND_LONGCLICK_BLOCK = f"""
        // {MARKER}: guarda a posição do toque pra usar no long-press,
        // e nunca consome o evento aqui (return false) - scroll, zoom
        // por pinça e clique normal continuam funcionando exatamente
        // como antes.
        newWebView.setOnTouchListener {{ _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {{
                lastTouchX = event.x
                lastTouchY = event.y
            }}
            false
        }}

        // {MARKER}: ponte JS -> Kotlin usada só pra descobrir se o
        // ponto tocado tem uma imagem ou vídeo por baixo (ver
        // MEDIA_DETECTION_JS). Não expõe nada além disso.
        newWebView.addJavascriptInterface(MediaLongPressBridge(), "XaulinXsMediaBridge")

        newWebView.setOnLongClickListener {{
            val current = webView ?: return@setOnLongClickListener false
            val scale = current.scale.takeIf {{ it > 0f }} ?: 1f
            val cssX = (lastTouchX / scale).toInt()
            val cssY = (lastTouchY / scale).toInt()
            current.evaluateJavascript(mediaDetectionJs(cssX, cssY), null)
            // Consome o long-press: em vez do menu nativo de seleção de
            // texto do WebView, este app decide mostrar (ou não) o
            // popup compacto assim que o JS acima responder de forma
            // assíncrona via MediaLongPressBridge.onMediaFound().
            true
        }}
"""

BRIDGE_AND_HELPERS_BLOCK = f'''
    // {MARKER}
    /**
     * Ponte chamada pelo JavaScript injetado no long-press
     * (mediaDetectionJs). Roda numa thread interna do WebView, então
     * qualquer coisa que toque em Views precisa passar pelo
     * mediaMenuHandler antes.
     */
    private inner class MediaLongPressBridge {{
        @JavascriptInterface
        fun onMediaFound(type: String, src: String) {{
            if (src.isBlank()) return
            mediaMenuHandler.post {{ showMediaContextMenu(type, src) }}
        }}
    }}

    /**
     * JS injetado no ponto tocado (em CSS px): sobe a árvore de
     * ancestrais a partir de document.elementFromPoint() procurando,
     * nesta ordem, uma <img>, um <video>/<source> (usando currentSrc
     * quando disponível - é o que reflete a fonte realmente em
     * reprodução) ou um elemento com background-image no CSS
     * computado (comum em galerias que não usam <img> de verdade).
     * Resolve URLs relativas com "new URL(..., location.href)" antes
     * de devolver, pra sempre chegar no Kotlin como link absoluto.
     */
    private fun mediaDetectionJs(x: Int, y: Int): String = """
        (function() {{
            try {{
                var el = document.elementFromPoint($x, $y);
                while (el) {{
                    var tag = el.tagName ? el.tagName.toUpperCase() : '';
                    if (tag === 'IMG' && el.src) {{
                        XaulinXsMediaBridge.onMediaFound('image', new URL(el.src, location.href).href);
                        return;
                    }}
                    if (tag === 'VIDEO') {{
                        var vsrc = el.currentSrc || el.src || '';
                        if (!vsrc) {{
                            var sourceEl = el.querySelector('source');
                            if (sourceEl && sourceEl.src) vsrc = sourceEl.src;
                        }}
                        if (vsrc) {{
                            XaulinXsMediaBridge.onMediaFound('video', new URL(vsrc, location.href).href);
                            return;
                        }}
                    }}
                    if (tag === 'SOURCE' && el.parentElement && el.parentElement.tagName === 'VIDEO' && el.src) {{
                        XaulinXsMediaBridge.onMediaFound('video', new URL(el.src, location.href).href);
                        return;
                    }}
                    var bg = window.getComputedStyle(el).backgroundImage;
                    if (bg && bg !== 'none') {{
                        var match = bg.match(/url\\(["']?(.*?)["']?\\)/);
                        if (match && match[1]) {{
                            XaulinXsMediaBridge.onMediaFound('image', new URL(match[1], location.href).href);
                            return;
                        }}
                    }}
                    el = el.parentElement;
                }}
            }} catch (e) {{}}
        }})();
    """.trimIndent()

    /**
     * Popup compacto (AlertDialog.setItems, mesmo padrão sem Material
     * já usado no resto do app) mostrado quando o long-press acha uma
     * imagem ou vídeo sob o dedo. type é "image" ou "video" - só muda
     * o rótulo do primeiro item (Baixar imagem / Baixar vídeo).
     */
    private fun showMediaContextMenu(type: String, url: String) {{
        val downloadLabel = if (type == "video") {{
            getString(R.string.media_menu_download_video)
        }} else {{
            getString(R.string.media_menu_download_image)
        }}
        val options = arrayOf(
            downloadLabel,
            getString(R.string.media_menu_copy_link),
            getString(R.string.media_menu_new_webview)
        )
        AlertDialog.Builder(this)
            .setItems(options) {{ _, which ->
                when (which) {{
                    0 -> downloadMediaDirect(url)
                    1 -> copyMediaLinkToClipboard(url)
                    2 -> recreateWebViewWithUrl(url)
                }}
            }}
            .show()
    }}

    /**
     * Dispara o download da mídia direto pela URL captada (sem passar
     * pelo WebView.setDownloadListener, já que aqui não existe uma
     * navegação/Content-Disposition do servidor disparando o
     * download - é um GET direto no link da imagem/vídeo). Só funciona
     * quando a URL é acessível publicamente sem autenticação extra
     * além dos cookies já salvos (CookieManager, usado dentro de
     * BrowserDownloadManager) - daí a ressalva "se o site permitir".
     */
    private fun downloadMediaDirect(url: String) {{
        try {{
            BrowserDownloadManager.startDownload(
                this,
                url,
                webView?.settings?.userAgentString,
                null,
                null
            )
        }} catch (e: Exception) {{
            Toast.makeText(this, R.string.media_menu_download_failed, Toast.LENGTH_SHORT).show()
        }}
    }}

    /** Mesmo padrão de HistoryActivity.copyLinkToClipboard(). */
    private fun copyMediaLinkToClipboard(url: String) {{
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), url))
        Toast.makeText(this, R.string.media_menu_link_copied, Toast.LENGTH_SHORT).show()
    }}

    /**
     * "Abrir em novo WebView": não é uma nova aba - é literalmente
     * destruir a instância atual de WebView (removendo do
     * webViewContainer e chamando destroy(), pra liberar o processo
     * renderer/sandbox associado) e recriar do zero via
     * initWebView(), que já sabe carregar pendingExternalUrl assim
     * que a nova instância termina de ser montada.
     */
    private fun recreateWebViewWithUrl(url: String) {{
        webView?.let {{ old ->
            webViewContainer.removeView(old)
            old.destroy()
        }}
        webView = null
        pendingExternalUrl = url
        showWebView()
        initWebView()
    }}
'''


def ensure_imports(text: str) -> str:
    for imp in IMPORTS_TO_ADD:
        if imp not in text:
            text = text.replace(
                "import java.io.File",
                f"{imp}\nimport java.io.File",
                1,
            )
    return text


def patch_main_activity():
    text = MAIN_ACTIVITY.read_text(encoding="utf-8")
    if MARKER in text:
        print("MainActivity.kt: já aplicado, pulando.")
        return

    text = ensure_imports(text)

    # Campos novos, logo depois de pendingExternalUrl.
    anchor_fields = "    private var pendingExternalUrl: String? = null\n"
    if anchor_fields not in text:
        raise SystemExit("Âncora de campos (pendingExternalUrl) não encontrada - revisar script.")
    text = text.replace(anchor_fields, anchor_fields + FIELDS_BLOCK, 1)

    # Touch + long click listener, logo depois do allowContentAccess e
    # antes do webViewClient existente.
    anchor_touch = "        newWebView.webViewClient = object : WebViewClient() {"
    if anchor_touch not in text:
        raise SystemExit("Âncora do webViewClient não encontrada - revisar script.")
    text = text.replace(anchor_touch, TOUCH_AND_LONGCLICK_BLOCK + "\n" + anchor_touch, 1)

    # Bridge + helpers: inseridos logo antes de updateDesktopModeButtonLabel,
    # que já vem logo depois do fechamento de initWebView().
    anchor_helpers = "    private fun updateDesktopModeButtonLabel() {"
    if anchor_helpers not in text:
        raise SystemExit("Âncora de updateDesktopModeButtonLabel não encontrada - revisar script.")
    text = text.replace(anchor_helpers, BRIDGE_AND_HELPERS_BLOCK + "\n" + anchor_helpers, 1)

    MAIN_ACTIVITY.write_text(text, encoding="utf-8")
    print("MainActivity.kt: menu de long-press em imagem/vídeo adicionado.")


if __name__ == "__main__":
    patch_strings()
    patch_main_activity()
    print("Concluído.")
