#!/usr/bin/env python3
"""
Aplica no repo local (Navegador) duas mudanças:

1) Popup de editar/excluir mecanismo de busca customizado na tela de
   Configurações (pressionar-e-segurar sobre o RadioButton do mecanismo),
   no mesmo padrão do popup de editar atalho já existente na Home
   (MainActivity.showEditShortcutDialog). Só funciona para mecanismos
   customizados - os fixos (Google, Bing, DuckDuckGo) continuam sem
   editar/excluir.

2) Troca app/src/main/assets/adblock/hosts_block.txt (lista curada
   manualmente, ~90 domínios) por uma lista robusta de verdade: baixa
   direto do repositório StevenBlack/hosts (fusão de várias fontes de
   ads/malware/rastreamento, ~76 mil domínios), no MESMO formato de
   arquivo já usado pelo AdBlockManager (um domínio por linha, comentários
   com "#") - não precisa mudar nenhum código do AdBlockManager.kt.

Uso (dentro da pasta do projeto Navegador, no Termux):
    python3 fix_search_engine_edit_and_robust_adblock.py

Idempotente: pode rodar de novo sem duplicar nada.
"""
import os
import sys
import urllib.request

REPO_ROOT = os.getcwd()

SEARCH_ENGINE_MANAGER = os.path.join(
    REPO_ROOT, "app/src/main/java/com/xaulinxs/funcoes/SearchEngineManager.java"
)
SETTINGS_ACTIVITY = os.path.join(
    REPO_ROOT, "app/src/main/java/com/xaulinxs/aosp/browser/SettingsActivity.kt"
)
STRINGS_XML = os.path.join(REPO_ROOT, "app/src/main/res/values/strings.xml")
HOSTS_BLOCK = os.path.join(REPO_ROOT, "app/src/main/assets/adblock/hosts_block.txt")

HOSTS_SOURCE_URL = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"

IGNORED_HOST_NAMES = {
    "localhost", "localhost.localdomain", "local", "broadcasthost", "0.0.0.0",
}


def die(msg):
    print("ERRO: " + msg, file=sys.stderr)
    sys.exit(1)


def read(path):
    if not os.path.exists(path):
        die(f"arquivo não encontrado: {path} (rode este script na raiz do projeto Navegador)")
    with open(path, "r", encoding="utf-8") as f:
        return f.read()


def write(path, content):
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)


# --- 1) SearchEngineManager.java: updateCustomEngine / removeCustomEngine ---

SEM_NEW_METHODS = '''
    public static void removeCustomEngine(Context context, Engine engine) {
        List<Engine> current = customEngines(context);
        current.removeIf(e -> e.name.equals(engine.name) && e.urlTemplate.equals(engine.urlTemplate));
        persistCustom(context, current);
        // Se o motor removido era o ativo, volta pro padrão (Google).
        if (prefs(context).getString(KEY_ACTIVE_ENGINE, "Google").equals(engine.name)) {
            setActiveEngine(context, builtInEngines().get(0).name);
        }
    }

    /**
     * Edita um mecanismo customizado no lugar, preservando a posição na
     * lista - usado pelo popup de "pressionar e segurar" em cima de um
     * mecanismo na tela de Configurações. Mesmo padrão de
     * ShortcutManager.updateShortcut(). Se o motor editado era o ativo,
     * a seleção ativa acompanha o novo nome.
     */
    public static void updateCustomEngine(Context context, Engine oldEngine, String newName, String newUrl) {
        List<Engine> current = customEngines(context);
        for (int i = 0; i < current.size(); i++) {
            Engine e = current.get(i);
            if (e.name.equals(oldEngine.name) && e.urlTemplate.equals(oldEngine.urlTemplate)) {
                current.set(i, new Engine(newName, newUrl, false));
                break;
            }
        }
        persistCustom(context, current);
        if (prefs(context).getString(KEY_ACTIVE_ENGINE, "Google").equals(oldEngine.name)) {
            setActiveEngine(context, newName);
        }
    }

    private static void persistCustom(Context context, List<Engine> engines) {
        JSONArray array = new JSONArray();
        try {
            for (Engine engine : engines) {
                JSONObject obj = new JSONObject();
                obj.put("name", engine.name);
                obj.put("url", engine.urlTemplate);
                array.put(obj);
            }
        } catch (JSONException ignored) {
        }
        prefs(context).edit().putString(KEY_CUSTOM_ENGINES, array.toString()).apply();
    }

    public static Engine activeEngine(Context context) {'''

OLD_ADD_CUSTOM_ENGINE = '''    public static void addCustomEngine(Context context, String name, String urlTemplate) {
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
    }'''

NEW_ADD_CUSTOM_ENGINE = '''    public static void addCustomEngine(Context context, String name, String urlTemplate) {
        List<Engine> current = customEngines(context);
        current.add(new Engine(name, urlTemplate, false));
        persistCustom(context, current);
    }'''


def patch_search_engine_manager():
    content = read(SEARCH_ENGINE_MANAGER)
    if "updateCustomEngine" in content:
        print("[skip] SearchEngineManager.java já tem updateCustomEngine/removeCustomEngine.")
        return
    marker = "    public static Engine activeEngine(Context context) {"
    if marker not in content:
        die("SearchEngineManager.java: não achei o método activeEngine() para inserir o patch.")
    content = content.replace(marker, SEM_NEW_METHODS.strip("\n") + "\n", 1)

    if OLD_ADD_CUSTOM_ENGINE in content:
        content = content.replace(OLD_ADD_CUSTOM_ENGINE, NEW_ADD_CUSTOM_ENGINE, 1)
    else:
        print("[aviso] addCustomEngine() não bateu com o formato esperado - deixei como estava "
              "(a lib continua funcionando, só não foi simplificada pra usar persistCustom).")

    write(SEARCH_ENGINE_MANAGER, content)
    print("[ok] SearchEngineManager.java atualizado.")


# --- 2) SettingsActivity.kt: long-click + popup de editar/excluir ---

OLD_RENDER_TAIL = '''            radio.setOnClickListener {
                SearchEngineManager.setActiveEngine(this, engine.name)
            }
            searchEngineGroup.addView(radio)
        }
    }'''

NEW_RENDER_TAIL = '''            radio.setOnClickListener {
                SearchEngineManager.setActiveEngine(this, engine.name)
            }
            // Mesmo padrão do atalho na Home (MainActivity.showEditShortcutDialog):
            // pressionar-e-segurar abre popup de editar/excluir. Só faz
            // sentido para mecanismos customizados - os fixos (Google,
            // Bing, DuckDuckGo) não são editáveis nem removíveis.
            if (!engine.builtIn) {
                radio.setOnLongClickListener {
                    showEditEngineDialog(engine)
                    true
                }
            }
            searchEngineGroup.addView(radio)
        }
    }

    /**
     * Popup de pressionar-e-segurar em cima de um mecanismo de busca
     * customizado na tela de Configurações: permite editar nome/link
     * (Salvar) ou excluir o mecanismo. Mesmo esqueleto de
     * showAddEngineDialog(), pré-preenchido com os valores atuais e com
     * um terceiro botão neutro pra exclusão - idêntico ao padrão de
     * MainActivity.showEditShortcutDialog() para atalhos da Home.
     */
    private fun showEditEngineDialog(engine: SearchEngineManager.Engine) {
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        val padding = (16 * resources.displayMetrics.density).toInt()
        container.setPadding(padding, padding, padding, padding)

        val nameInput = EditText(this)
        nameInput.hint = getString(R.string.add_search_engine_name_hint)
        nameInput.setText(engine.name)
        container.addView(nameInput)

        val urlInput = EditText(this)
        urlInput.hint = getString(R.string.add_search_engine_url_hint)
        urlInput.setText(engine.urlTemplate)
        container.addView(urlInput)

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_edit_search_engine)
            .setView(container)
            .setPositiveButton(R.string.shortcuts_dialog_save) { _, _ ->
                val name = nameInput.text.toString().trim()
                var url = urlInput.text.toString().trim()
                if (name.isEmpty() || url.isEmpty()) {
                    Toast.makeText(this, R.string.shortcuts_dialog_invalid, Toast.LENGTH_SHORT).show()
                } else {
                    if (!url.contains("://")) {
                        url = "https://$url"
                    }
                    SearchEngineManager.updateCustomEngine(this, engine, name, url)
                    renderSearchEngines()
                }
            }
            .setNeutralButton(R.string.shortcuts_dialog_delete) { _, _ ->
                SearchEngineManager.removeCustomEngine(this, engine)
                renderSearchEngines()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }'''


def patch_settings_activity():
    content = read(SETTINGS_ACTIVITY)
    if "showEditEngineDialog" in content:
        print("[skip] SettingsActivity.kt já tem showEditEngineDialog.")
        return
    if OLD_RENDER_TAIL not in content:
        die("SettingsActivity.kt: não achei o fim de renderSearchEngines() no formato esperado.")
    content = content.replace(OLD_RENDER_TAIL, NEW_RENDER_TAIL, 1)
    write(SETTINGS_ACTIVITY, content)
    print("[ok] SettingsActivity.kt atualizado.")


def patch_strings_xml():
    content = read(STRINGS_XML)
    if "settings_edit_search_engine" in content:
        print("[skip] strings.xml já tem settings_edit_search_engine.")
        return
    marker = '    <string name="settings_add_search_engine">Adicionar mecanismo de busca</string>'
    if marker not in content:
        die("strings.xml: não achei settings_add_search_engine para inserir a string nova ao lado.")
    content = content.replace(
        marker,
        marker + '\n    <string name="settings_edit_search_engine">Editar mecanismo de busca</string>',
        1,
    )
    write(STRINGS_XML, content)
    print("[ok] strings.xml atualizado.")


# --- 3) Lista de adblock robusta (StevenBlack/hosts) ---

def build_robust_adblock_list():
    print(f"Baixando lista de {HOSTS_SOURCE_URL} ...")
    try:
        with urllib.request.urlopen(HOSTS_SOURCE_URL, timeout=60) as resp:
            raw = resp.read().decode("utf-8", errors="ignore")
    except Exception as exc:
        die(f"não consegui baixar a lista StevenBlack/hosts: {exc}\n"
            "       (confira a internet do aparelho; o hosts_block.txt atual foi mantido)")

    domains = set()
    for line in raw.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split()
        if len(parts) < 2:
            continue
        ip, host = parts[0], parts[1]
        if ip not in ("0.0.0.0", "127.0.0.1"):
            continue
        host = host.lower()
        if host in IGNORED_HOST_NAMES or host.startswith("ip6-") or host.startswith("fe80::") or host.startswith("ff0"):
            continue
        domains.add(host)

    if len(domains) < 10000:
        die(f"a lista baixada só trouxe {len(domains)} domínios - parece truncada/errada, "
            "mantendo hosts_block.txt atual sem alterações.")

    header = (
        "# Lista de bloqueio do adblock embutido (ver AdBlockManager.kt).\n"
        "# Um domínio por linha. Bloquear um domínio aqui bloqueia automaticamente\n"
        "# TODOS os subdomínios dele também (ver a lógica de sufixo em\n"
        "# AdBlockManager.isBlocked).\n"
        "# Linhas em branco e começadas com \"#\" são ignoradas.\n"
        "#\n"
        "# Fonte: StevenBlack/hosts (unified hosts - ads + malware + rastreamento),\n"
        f"# {HOSTS_SOURCE_URL}\n"
        "# Convertido de formato hosts (\"0.0.0.0 dominio\") para domínio puro,\n"
        "# um por linha. Pra atualizar no futuro, é só rodar este script de novo.\n\n"
    )
    body = "\n".join(sorted(domains)) + "\n"
    write(HOSTS_BLOCK, header + body)
    print(f"[ok] hosts_block.txt substituído por lista robusta ({len(domains)} domínios).")


def main():
    patch_search_engine_manager()
    patch_settings_activity()
    patch_strings_xml()
    build_robust_adblock_list()
    print("\nPronto. Agora é só compilar: ./gradlew assembleDebug")


if __name__ == "__main__":
    main()
