package com.xaulinxs.funcoes;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Motores de busca fixos + customizados, persistidos em SharedPreferences
 * (mesma abordagem simples usada pelo WebviewShell original pra
 * cor/transparência/fonte). Cada motor tem um nome e uma URL-template com
 * "%s" no lugar do termo de busca. O motor ativo também fica salvo aqui.
 */
public final class SearchEngineManager {

    private static final String PREFS_NAME = "xaulinxs_browser_prefs";
    private static final String KEY_CUSTOM_ENGINES = "custom_search_engines";
    private static final String KEY_ACTIVE_ENGINE = "active_search_engine";

    public static final class Engine {
        public final String name;
        public final String urlTemplate;
        public final boolean builtIn;

        public Engine(String name, String urlTemplate, boolean builtIn) {
            this.name = name;
            this.urlTemplate = urlTemplate;
            this.builtIn = builtIn;
        }
    }

    private SearchEngineManager() {}

    /** Motores fixos: Google, Bing, DuckDuckGo. */
    public static List<Engine> builtInEngines() {
        List<Engine> list = new ArrayList<>();
        list.add(new Engine("Google", "https://www.google.com/search?q=%s", true));
        list.add(new Engine("Bing", "https://www.bing.com/search?q=%s", true));
        list.add(new Engine("DuckDuckGo", "https://duckduckgo.com/?q=%s", true));
        return list;
    }

    /** Todos os motores disponíveis: fixos + customizados adicionados pelo usuário. */
    public static List<Engine> allEngines(Context context) {
        List<Engine> list = builtInEngines();
        list.addAll(customEngines(context));
        return list;
    }

    public static List<Engine> customEngines(Context context) {
        List<Engine> list = new ArrayList<>();
        String raw = prefs(context).getString(KEY_CUSTOM_ENGINES, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                list.add(new Engine(obj.getString("name"), obj.getString("url"), false));
            }
        } catch (JSONException ignored) {
            // Dados corrompidos: ignora e trata como lista vazia.
        }
        return list;
    }

    public static void addCustomEngine(Context context, String name, String urlTemplate) {
        List<Engine> current = customEngines(context);
        current.add(new Engine(name, urlTemplate, false));
        JSONArray array = new JSONArray();
        try {
            for (Engine engine : current) {
                JSONObject obj = new JSONObject();
                obj.put("name", engine.name);
                obj.put("url", engine.urlTemplate);
                array.put(obj);
            }
        } catch (JSONException ignored) {
        }
        prefs(context).edit().putString(KEY_CUSTOM_ENGINES, array.toString()).apply();
    }

    public static Engine activeEngine(Context context) {
        String activeName = prefs(context).getString(KEY_ACTIVE_ENGINE, "Google");
        for (Engine engine : allEngines(context)) {
            if (engine.name.equals(activeName)) return engine;
        }
        return builtInEngines().get(0);
    }

    public static void setActiveEngine(Context context, String name) {
        prefs(context).edit().putString(KEY_ACTIVE_ENGINE, name).apply();
    }

    /** Monta a URL de busca substituindo "%s" pelo termo (já URL-encoded). */
    public static String buildSearchUrl(Context context, String query) {
        Engine engine = activeEngine(context);
        String encoded = android.net.Uri.encode(query);
        return engine.urlTemplate.replace("%s", encoded);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
