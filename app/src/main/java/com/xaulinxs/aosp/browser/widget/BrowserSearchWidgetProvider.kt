package com.xaulinxs.aosp.browser.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.xaulinxs.aosp.browser.MainActivity
import com.xaulinxs.aosp.browser.R

/**
 * Widget de busca 4x1 (estilo Chromium): uma cápsula longa que, ao ser
 * tocada em qualquer ponto, abre a MainActivity já com o campo de busca
 * da Home focado e o teclado aberto - não carrega nenhuma URL sozinho,
 * é só um atalho rápido pra começar a digitar.
 *
 * O widget usa um único RemoteViews (widget_browser_search) reaproveitado
 * pra todas as instâncias/tamanhos - o próprio launcher estica o layout
 * (LinearLayout com peso) conforme o usuário redimensiona, então não
 * precisa de lógica de "vários layouts por tamanho" nem de
 * onAppWidgetOptionsChanged.
 */
class BrowserSearchWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (widgetId in appWidgetIds) {
            appWidgetManager.updateAppWidget(widgetId, buildRemoteViews(context, widgetId))
        }
    }

    private fun buildRemoteViews(context: Context, widgetId: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_browser_search)

        val focusSearchIntent = buildOpenIntent(context) { putExtra(MainActivity.EXTRA_FOCUS_SEARCH, true) }
        val voiceSearchIntent = buildOpenIntent(context) { putExtra(MainActivity.EXTRA_START_VOICE_SEARCH, true) }

        // requestCode = widgetId (e widgetId + 1 pro mic, pra não colidir
        // com o PendingIntent da área de busca) garante PendingIntents
        // distintos por instância do widget e por ícone. FLAG_IMMUTABLE é
        // obrigatório a partir do Android 12 (API 31) para PendingIntents
        // que não precisam ser preenchidos (fillIn) por quem os recebe -
        // o mesmo tipo de exigência de segurança que já se aplicou à
        // notificação de download.
        val focusSearchPendingIntent = PendingIntent.getActivity(
            context, widgetId, focusSearchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val voiceSearchPendingIntent = PendingIntent.getActivity(
            context, widgetId + 1, voiceSearchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Duas regiões de clique independentes dentro do mesmo widget: o
        // resto da cápsula foca a busca, o ícone de microfone abre direto
        // no popup de busca por voz.
        views.setOnClickPendingIntent(R.id.widgetContentArea, focusSearchPendingIntent)
        views.setOnClickPendingIntent(R.id.widgetMicIcon, voiceSearchPendingIntent)
        return views
    }

    private inline fun buildOpenIntent(context: Context, extras: Intent.() -> Unit): Intent {
        return Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            // FLAG_ACTIVITY_NEW_TASK é obrigatório: o PendingIntent é
            // disparado a partir do processo do launcher, não do nosso
            // app, então precisa iniciar em sua própria task.
            // CLEAR_TOP + SINGLE_TOP reaproveita a MainActivity se ela já
            // estiver na pilha (mesmo launchMode="singleTask" do
            // AndroidManifest), evitando empilhar instâncias duplicadas
            // toda vez que o widget é tocado.
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            extras()
        }
    }
}
