package com.xaulinxs.funcoes;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Atalhos de sites (estilo "sites mais visitados" do Chromium): cada um
 * tem um nome de exibição e uma URL, adicionados manualmente pelo usuário
 * através do botão "+" do card de atalhos da Home. Persistidos em
 * SharedPreferences como JSON, mesmo padrão já usado por
 * SearchEngineManager pra motores de busca customizados.
 */
public final class ShortcutManager {

    private static final String PREFS_NAME = "xaulinxs_browser_prefs";
    private static final String KEY_SHORTCUTS = "site_shortcuts";

    public static final class Shortcut {
        public final String name;
        public final String url;

        public Shortcut(String name, String url) {
            this.name = name;
            this.url = url;
        }
    }

    private ShortcutManager() {}

    public static List<Shortcut> allShortcuts(Context context) {
        List<Shortcut> list = new ArrayList<>();
        String raw = prefs(context).getString(KEY_SHORTCUTS, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                list.add(new Shortcut(obj.getString("name"), obj.getString("url")));
            }
        } catch (JSONException ignored) {
            // Dados corrompidos: ignora e trata como lista vazia.
        }
        return list;
    }

    public static void addShortcut(Context context, String name, String url) {
        List<Shortcut> current = allShortcuts(context);
        current.add(new Shortcut(name, url));
        persist(context, current);
    }

    public static void removeShortcut(Context context, Shortcut shortcut) {
        List<Shortcut> current = allShortcuts(context);
        current.removeIf(s -> s.name.equals(shortcut.name) && s.url.equals(shortcut.url));
        persist(context, current);
    }

    private static void persist(Context context, List<Shortcut> shortcuts) {
        JSONArray array = new JSONArray();
        try {
            for (Shortcut shortcut : shortcuts) {
                JSONObject obj = new JSONObject();
                obj.put("name", shortcut.name);
                obj.put("url", shortcut.url);
                array.put(obj);
            }
        } catch (JSONException ignored) {
        }
        prefs(context).edit().putString(KEY_SHORTCUTS, array.toString()).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
