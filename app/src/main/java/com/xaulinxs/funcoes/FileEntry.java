package com.xaulinxs.funcoes;

import java.io.File;
import java.util.Locale;

/**
 * Uma linha do file manager próprio: pode ser uma pasta, um arquivo, ou a
 * entrada ".." de voltar. Portado de com.xaulinxs.webviewshell.FileEntry
 * (projeto WebviewShell, abandonado) sem alterações de lógica, só de pacote.
 */
public class FileEntry {
    public enum Kind { UP, FOLDER, FILE }

    public final Kind kind;
    public final File file;

    public FileEntry(Kind kind, File file) {
        this.kind = kind;
        this.file = file;
    }

    public String label() {
        return kind == Kind.UP ? ".." : file.getName();
    }

    public String extension() {
        String name = file.getName().toLowerCase(Locale.getDefault());
        int dot = name.lastIndexOf('.');
        return dot >= 0 && dot < name.length() - 1 ? name.substring(dot + 1) : "";
    }

    public boolean isApk() {
        return extension().equals("apk");
    }

    public String readableSize() {
        long bytes = file.length();
        if (bytes <= 0) return "";
        String[] units = {"B", "KB", "MB", "GB"};
        double size = bytes;
        int unit = 0;
        while (size >= 1024 && unit < units.length - 1) {
            size /= 1024;
            unit++;
        }
        return String.format(Locale.getDefault(), "%.1f %s", size, units[unit]);
    }
}
