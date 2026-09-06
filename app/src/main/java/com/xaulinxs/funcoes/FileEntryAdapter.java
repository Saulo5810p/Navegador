package com.xaulinxs.funcoes;

import android.content.Context;
import android.graphics.PorterDuff;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import com.xaulinxs.aosp.browser.R;

import java.util.List;

/**
 * Adapter do ListView do file manager, com ícones tintados por tipo de
 * arquivo. Portado de com.xaulinxs.webviewshell.FileEntryAdapter, removendo
 * só o suporte a Typeface customizado (fora do escopo desta fase no
 * projeto Navegador).
 */
public class FileEntryAdapter extends ArrayAdapter<FileEntry> {

    public FileEntryAdapter(Context context, List<FileEntry> entries) {
        super(context, 0, entries);
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View row = convertView;
        if (row == null) {
            row = LayoutInflater.from(getContext()).inflate(R.layout.item_file_entry, parent, false);
        }
        FileEntry entry = getItem(position);
        if (entry == null) return row;

        ImageView icon = row.findViewById(R.id.entry_icon);
        TextView name = row.findViewById(R.id.entry_name);
        TextView subtitle = row.findViewById(R.id.entry_subtitle);

        name.setText(entry.label());

        icon.clearColorFilter();
        switch (entry.kind) {
            case UP:
            case FOLDER:
                icon.setImageResource(R.drawable.ic_folder_retro);
                subtitle.setText("");
                break;
            default:
                icon.setImageResource(R.drawable.ic_file_generic);
                icon.setColorFilter(colorForExtension(entry.extension()), PorterDuff.Mode.SRC_ATOP);
                subtitle.setText(entry.readableSize());
                break;
        }
        return row;
    }

    /** Tinge o ícone genérico por tipo de arquivo, pra diferenciar sem precisar de um SVG por extensão. */
    private int colorForExtension(String ext) {
        switch (ext) {
            case "apk": return 0xFF6FA834;
            case "ttf": case "otf": return 0xFF8A5FBE;
            case "jpg": case "jpeg": case "png": case "gif": case "webp": case "bmp": return 0xFF4C8BC9;
            case "zip": case "rar": case "7z": case "tar": case "gz": return 0xFFD08A2E;
            case "mp3": case "wav": case "ogg": case "m4a": return 0xFFC24B7C;
            case "mp4": case "mkv": case "webm": case "avi": return 0xFF4B9C8E;
            case "pdf": return 0xFFB0402E;
            default: return 0xFF9A9A9A;
        }
    }
}
