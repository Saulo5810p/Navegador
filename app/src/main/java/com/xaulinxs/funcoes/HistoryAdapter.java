package com.xaulinxs.funcoes;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import com.xaulinxs.aosp.browser.R;

import java.util.List;
import java.util.Locale;

/** Adapta a lista de {@link HistoryEntry} para o ListView da tela de Histórico. */
public class HistoryAdapter extends ArrayAdapter<HistoryEntry> {

    public HistoryAdapter(Context context, List<HistoryEntry> entries) {
        super(context, 0, entries);
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View row = convertView;
        if (row == null) {
            row = LayoutInflater.from(getContext()).inflate(R.layout.item_history, parent, false);
        }
        HistoryEntry entry = getItem(position);
        if (entry == null) return row;

        TextView initial = row.findViewById(R.id.historyInitial);
        TextView title = row.findViewById(R.id.historyItemTitle);
        TextView url = row.findViewById(R.id.historyItemUrl);

        String displayTitle = entry.getTitle() != null ? entry.getTitle() : "";
        initial.setText(displayTitle.isEmpty() ? "?" : displayTitle.substring(0, 1).toUpperCase(Locale.getDefault()));
        title.setText(displayTitle);
        url.setText(entry.getUrl());

        return row;
    }
}
