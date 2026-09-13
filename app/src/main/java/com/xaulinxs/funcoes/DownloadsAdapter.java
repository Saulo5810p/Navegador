package com.xaulinxs.funcoes;

import android.app.DownloadManager;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.TextView;

import com.xaulinxs.aosp.browser.R;
import com.xaulinxs.aosp.browser.widget.DownloadProgressCircleView;
import com.xaulinxs.funcoes.download.ByteFormatter;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/**
 * Adapta a lista de {@link DownloadEntry} (todos os status vindos do
 * DownloadManager - não só concluídos) pro ListView de DownloadsActivity.
 *
 * Por status:
 *   RUNNING/PENDING   -> bolinha de progresso se enchendo + linha "X MB / Y MB"
 *                        PISCANDO + botão de abrir desabilitado (cancelar via lixeira)
 *   PAUSED (sistema)  -> bolinha parada + subtítulo com o motivo (rede/tentativa)
 *                        + botão de abrir desabilitado (cancelar via lixeira)
 *   FAILED            -> subtítulo "Baixar novamente" + botão de abrir vira retry
 *   SUCCESSFUL        -> comportamento original (abrir + excluir)
 *
 * O botão de lixeira funciona sempre, em qualquer status: DownloadManager.remove()
 * cancela o download se ainda estiver ativo, ou apaga o arquivo se já tiver
 * concluído - é a única ação de "excluir/cancelar" exposta pela API pública
 * do DownloadManager (não existe pausar/retomar manual).
 */
public class DownloadsAdapter extends ArrayAdapter<DownloadEntry> {

    /** Callback dos botões de ação de cada item, implementado pela DownloadsActivity. */
    public interface OnActionListener {
        void onOpen(DownloadEntry entry);
        void onDelete(DownloadEntry entry);
        void onRetry(DownloadEntry entry);
    }

    private final OnActionListener listener;

    public DownloadsAdapter(Context context, List<DownloadEntry> entries, OnActionListener listener) {
        super(context, 0, entries);
        this.listener = listener;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View row = convertView;
        if (row == null) {
            row = LayoutInflater.from(getContext()).inflate(R.layout.item_download, parent, false);
        }
        DownloadEntry entry = getItem(position);
        if (entry == null) return row;

        TextView title = row.findViewById(R.id.downloadTitle);
        TextView subtitle = row.findViewById(R.id.downloadSubtitle);
        ImageButton btnOpen = row.findViewById(R.id.btnOpenDownload);
        ImageButton btnDelete = row.findViewById(R.id.btnDeleteDownload);
        DownloadProgressCircleView circle = row.findViewById(R.id.downloadProgressCircle);

        title.setText(entry.title != null ? entry.title : "");

        subtitle.clearAnimation();
        circle.setVisibility(View.GONE);
        btnOpen.setAlpha(1f);
        btnOpen.setEnabled(true);
        btnOpen.setOnClickListener(v -> listener.onOpen(entry));
        btnOpen.setContentDescription(getContext().getString(R.string.downloads_open_content_description));

        switch (entry.status) {
            case DownloadManager.STATUS_RUNNING:
            case DownloadManager.STATUS_PENDING: {
                circle.setVisibility(View.VISIBLE);
                int percent = ByteFormatter.INSTANCE.progressPercent(entry.downloadedBytes, entry.sizeBytes);
                circle.setProgressPercent(percent);
                subtitle.setText(ByteFormatter.INSTANCE.formatProgress(entry.downloadedBytes, entry.sizeBytes));
                if (entry.isBlinking()) {
                    subtitle.startAnimation(blinkAnimation());
                }
                disableOpenButton(btnOpen);
                break;
            }
            case DownloadManager.STATUS_PAUSED: {
                circle.setVisibility(View.VISIBLE);
                circle.setProgressPercent(ByteFormatter.INSTANCE.progressPercent(entry.downloadedBytes, entry.sizeBytes));
                subtitle.setText(pausedReasonText(entry.reason));
                disableOpenButton(btnOpen);
                break;
            }
            case DownloadManager.STATUS_FAILED: {
                subtitle.setText(R.string.downloads_retry);
                btnOpen.setContentDescription(getContext().getString(R.string.downloads_retry));
                btnOpen.setOnClickListener(v -> listener.onRetry(entry));
                break;
            }
            case DownloadManager.STATUS_SUCCESSFUL:
            default: {
                String when = entry.timestamp > 0
                        ? DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                .format(new Date(entry.timestamp))
                        : "";
                String size = entry.readableSize();
                subtitle.setText(size.isEmpty() ? when : size + " • " + when);
                break;
            }
        }

        btnDelete.setOnClickListener(v -> listener.onDelete(entry));

        return row;
    }

    private void disableOpenButton(ImageButton btnOpen) {
        btnOpen.setEnabled(false);
        btnOpen.setAlpha(0.35f);
        btnOpen.setOnClickListener(null);
    }

    private AlphaAnimation blinkAnimation() {
        AlphaAnimation blink = new AlphaAnimation(1f, 0.25f);
        blink.setDuration(700);
        blink.setRepeatMode(Animation.REVERSE);
        blink.setRepeatCount(Animation.INFINITE);
        return blink;
    }

    private String pausedReasonText(int reason) {
        Context context = getContext();
        switch (reason) {
            case DownloadManager.PAUSED_WAITING_FOR_NETWORK:
                return context.getString(R.string.download_paused_waiting_network);
            case DownloadManager.PAUSED_WAITING_TO_RETRY:
                return context.getString(R.string.download_paused_waiting_retry);
            case DownloadManager.PAUSED_QUEUED_FOR_WIFI:
                return context.getString(R.string.download_paused_waiting_wifi);
            default:
                return context.getString(R.string.download_paused_unknown);
        }
    }
}
