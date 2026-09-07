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

        val openIntent = Intent(context, MainActivity::class.java).apply {
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
            putExtra(MainActivity.EXTRA_FOCUS_SEARCH, true)
        }

        // requestCode = widgetId garante um PendingIntent distinto por
        // instância do widget (relevante se o usuário adicionar mais de
        // uma cópia). FLAG_IMMUTABLE é obrigatório a partir do Android 12
        // (API 31) para PendingIntents que não precisam ser preenchidos
        // (fillIn) por quem os recebe - o mesmo tipo de exigência de
        // segurança que já se aplicou à notificação de download.
        val pendingIntent = PendingIntent.getActivity(
            context,
            widgetId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        views.setOnClickPendingIntent(R.id.widgetRoot, pendingIntent)
        return views
    }
}
