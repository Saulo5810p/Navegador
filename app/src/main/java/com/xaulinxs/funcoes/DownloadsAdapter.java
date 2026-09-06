package com.xaulinxs.funcoes;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import com.xaulinxs.aosp.browser.R;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/** Adapta a lista de {@link DownloadEntry} concluídos para o ListView de DownloadsActivity. */
public class DownloadsAdapter extends ArrayAdapter<DownloadEntry> {

    public DownloadsAdapter(Context context, List<DownloadEntry> entries) {
        super(context, 0, entries);
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

        title.setText(entry.title != null ? entry.title : "");
        String when = entry.timestamp > 0
                ? DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                        .format(new Date(entry.timestamp))
                : "";
        String size = entry.readableSize();
        String subtitleText = size.isEmpty() ? when : size + " • " + when;
        subtitle.setText(subtitleText);

        return row;
    }
}
