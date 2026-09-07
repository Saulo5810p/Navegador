package com.xaulinxs.funcoes;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.TextView;

import com.xaulinxs.aosp.browser.R;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/**
 * Adapta a lista de {@link DownloadEntry} concluídos para o ListView de
 * DownloadsActivity. Fase 4: cada linha tem botões explícitos de abrir e
 * excluir (em vez de toque simples/toque longo na linha inteira) -
 * OnActionListener repassa os cliques pra Activity, que decide o fluxo
 * de confirmação.
 */
public class DownloadsAdapter extends ArrayAdapter<DownloadEntry> {

    /** Callback dos botões de ação de cada item, implementado pela DownloadsActivity. */
    public interface OnActionListener {
        void onOpen(DownloadEntry entry);
        void onDelete(DownloadEntry entry);
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

        title.setText(entry.title != null ? entry.title : "");
        String when = entry.timestamp > 0
                ? DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                        .format(new Date(entry.timestamp))
                : "";
        String size = entry.readableSize();
        String subtitleText = size.isEmpty() ? when : size + " • " + when;
        subtitle.setText(subtitleText);

        btnOpen.setOnClickListener(v -> listener.onOpen(entry));
        btnDelete.setOnClickListener(v -> listener.onDelete(entry));

        return row;
    }
}
