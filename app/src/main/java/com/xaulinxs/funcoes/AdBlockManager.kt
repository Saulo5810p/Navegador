package com.xaulinxs.funcoes

import android.content.Context
import android.net.Uri
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Adblock embutido, leve de propósito: em vez de reimplementar um motor
 * de filtro tipo EasyList/uBlock (parsing de regras complexas, filtros
 * cosméticos via CSS/JS injetado, etc. - pesado e caro de manter), usa a
 * mesma técnica de "hosts file" que AdAway/Blokada usam no nível de DNS:
 * uma lista de domínios conhecidos de anúncio/rastreamento, comparada
 * contra o host de cada requisição que o WebView faz.
 *
 * Por que isso funciona bem com o nosso WebView "trocado" via
 * WebViewUpgrade: WebViewClient.shouldInterceptRequest() é uma API
 * pública padrão do android.webkit.WebView, disponível em QUALQUER
 * provider de WebView instalado (incluindo o AOSP que a gente injeta) -
 * não depende de nenhum hook nativo ou acesso interno ao Chromium/Blink,
 * então continua funcionando mesmo trocando a versão do WebView.
 *
 * Custo por requisição: no pior caso, algumas comparações de String num
 * HashSet (O(1) cada) - nada de regex, nada de I/O de rede, e com cache
 * de decisão por host (decisionCache) a maioria dos recursos repetidos
 * de uma mesma página (vários scripts/pixels do mesmo domínio de
 * anúncio) nem repete o cálculo. A lista em si (hosts_block.txt) só é
 * lida do disco (assets) uma vez, na primeira requisição depois que o
 * app abre.
 */
object AdBlockManager {

    private const val PREFS_NAME = "xaulinxs_browser_prefs"
    private const val KEY_ENABLED = "adblock_enabled"
    private const val ASSET_PATH = "adblock/hosts_block.txt"
    private const val DECISION_CACHE_MAX = 4000

    @Volatile
    private var blockedDomains: HashSet<String>? = null

    // Host -> já bloqueado ou não. Evita recalcular o "sobe a árvore de
    // subdomínios" pra cada imagem/script novo vindo do mesmo host de
    // anúncio dentro da mesma página.
    private val decisionCache = ConcurrentHashMap<String, Boolean>()

    private val blockedCountSession = AtomicInteger(0)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** Quantas requisições foram bloqueadas desde que o processo do app abriu (não persiste). */
    fun blockedCountThisSession(): Int = blockedCountSession.get()

    /**
     * true se a requisição pra essa URL deve ser bloqueada (resposta
     * vazia no lugar do recurso real). Só deve ser chamado pra recursos
     * que NÃO são o documento principal da página - nunca bloqueamos a
     * navegação principal, só sub-recursos (imagem/script/iframe de
     * anúncio), pra nunca deixar uma página em branco por engano.
     */
    fun shouldBlock(context: Context, url: String): Boolean {
        if (!isEnabled(context)) return false
        val host = try {
            Uri.parse(url).host?.lowercase()
        } catch (e: Exception) {
            null
        } ?: return false

        val blocked = decisionCache.getOrPut(host) { isHostBlocked(context, host) }
        if (decisionCache.size > DECISION_CACHE_MAX) {
            // Limite simples pra nunca crescer sem parar em sessões muito
            // longas com muitos sites diferentes - o custo de recalcular
            // depois é irrelevante perto do risco de memória sem limite.
            decisionCache.clear()
        }
        if (blocked) blockedCountSession.incrementAndGet()
        return blocked
    }

    /**
     * Confere o host e vai subindo pelos domínios pai (a.b.exemplo.com ->
     * b.exemplo.com -> exemplo.com) até achar uma correspondência exata
     * na lista ou sobrar só o TLD (nesse caso não bloqueia) - é assim que
     * bloquear "doubleclick.net" na lista também cobre
     * "static.doubleclick.net", "ad.doubleclick.net" etc. sem precisar
     * listar cada subdomínio manualmente.
     */
    private fun isHostBlocked(context: Context, host: String): Boolean {
        val domains = loadDomains(context)
        var candidate = host
        while (true) {
            if (domains.contains(candidate)) return true
            val dotIndex = candidate.indexOf('.')
            if (dotIndex < 0) return false
            candidate = candidate.substring(dotIndex + 1)
            if (!candidate.contains('.')) return false // não bloqueia TLD inteiro (ex: ".com")
        }
    }

    private fun loadDomains(context: Context): HashSet<String> {
        blockedDomains?.let { return it }
        synchronized(this) {
            blockedDomains?.let { return it }
            val set = HashSet<String>()
            try {
                context.applicationContext.assets.open(ASSET_PATH).bufferedReader().useLines { lines ->
                    for (rawLine in lines) {
                        val line = rawLine.trim()
                        if (line.isEmpty() || line.startsWith("#")) continue
                        set.add(line.lowercase())
                    }
                }
            } catch (e: IOException) {
                // Sem lista carregada: o adblock vira no-op (nunca bloqueia
                // nada) em vez de travar a navegação - falha segura.
            }
            blockedDomains = set
            return set
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
