# XaulinXs AOSP Browser — Relatório de mudanças

Base: commit `ae24ab7` (GitHub) + esta sessão.
Todo o código é Kotlin/Java/XML puro — **zero AppCompat, zero Material, zero Jetpack Compose**. Confirmado em `build.gradle.kts`: única dependência androidx é `androidx.core:core-ktx` (utilitário puro, sem Material Design).

---

## 1. Correção do crash de download (sessão anterior, já no repo)

**Arquivo:** `DownloadForegroundService.kt`

- **Causa:** `DownloadManager.COLUMN_LOCAL_URI` retorna `file:///storage/...`. Desde o Android 7 (API 24), expor um `file://` em `Intent.ACTION_VIEW` dentro de um `PendingIntent` de notificação viola o StrictMode e derruba o app (`FileUriExposedException`) assim que o usuário toca na notificação.
- **Correção:** novo método `resolveShareableUri()` converte o `file://` para `content://` via `FileProvider`, reaproveitando o mesmo `authority` (`${applicationId}.fileprovider`) e `file_paths.xml` já usados pelo `FileManagerActivity`. Nenhuma mudança de manifest necessária.

Já confirmado no repositório (commit `ae24ab7 update corrigindo download`).

---

## 2. Correções pontuais desta sessão

### 2.1 Botão de voltar do File Manager
**Arquivo:** `FileManagerActivity.java`

- Problema: `btn_up` só subia pasta; ao chegar na raiz, não fazia mais nada — só o botão físico fechava a tela.
- Correção: novo método `handleBackNavigation()`, compartilhado entre `btn_up` e `onBackPressed()`. Se ainda há pasta acima, sobe; se já está na raiz, chama `finish()` e volta para a tela anterior (ex: Configurações).

### 2.2 Mecanismo de busca com URL fixa
**Arquivos:** `SettingsActivity.kt`, `SearchEngineManager.java`, `strings.xml`

- Problema: o diálogo de "Adicionar mecanismo de busca" exigia `%s` na URL, rejeitando qualquer URL fixa (ex: `nosearch`, que só abre um endereço direto sem aceitar query).
- Correção: removida a exigência de `%s`. Agora aceita URL fixa (o `replace("%s", ...)` da lógica de busca já lidava bem com isso — simplesmente não substitui nada quando não há `%s`). `www.x` sem esquema é normalizado automaticamente para `https://www.x`.

### 2.3 Intent-filters para abrir qualquer arquivo local
**Arquivo:** `AndroidManifest.xml`

- Adicionado suporte para o app aparecer no seletor "Abrir com" de arquivos locais, igual qualquer navegador Android.
- **Decisão explícita do usuário:** sem restrição por extensão ou mimeType — `android:mimeType="*/*"` e `data android:scheme="file"` / `"content"` sem `pathPattern`. O app aceita **qualquer tipo de arquivo** local, não só `.html`/`.txt`.
- `MainActivity.kt`: `allowFileAccess`/`allowContentAccess` habilitados explicitamente na WebView (necessário porque o WebView AOSP embutido via `WebViewUpgrade` é um provider trocado manualmente, não herda os defaults do WebView de sistema com segurança).

### 2.4 Widget de busca 4x1 (estilo Chromium)
**Arquivos novos:**
- `drawable/bg_widget_search_bar.xml` (+ `-night`): cápsula com cantos arredondados (32dp)
- `layout/widget_browser_search.xml`: barra com ícone de busca + texto
- `xml/widget_browser_search_info.xml`: metadata (250dp mín., redimensionável horizontal/vertical, `targetCellWidth=4`/`Height=1`)
- `widget/BrowserSearchWidgetProvider.kt`: `PendingIntent` com `FLAG_IMMUTABLE`, abre `MainActivity` com `EXTRA_FOCUS_SEARCH=true`

`MainActivity.kt` trata esse extra em `handleViewIntent()` + `applyPendingSearchFocusIfNeeded()`: mostra a Home e foca automaticamente o teclado no campo de busca.

### 2.5 Card de atalhos de sites na Home
**Arquivos novos:**
- `widget/GradientShadowCardView.kt`: Custom View que desenha retângulo arredondado + gradiente linear + sombra via `Paint.setShadowLayer` (Canvas puro, sem elevation/Material). Raio de canto configurável via atributo XML `cornerRadiusDp` (reaproveitada depois na Fase 1).
- `funcoes/ShortcutManager.java`: persistência de atalhos (nome + URL) em `SharedPreferences`, como JSON.
- `layout/view_shortcuts_card.xml`, `layout/view_shortcut_item.xml`: card com botão circular "+" que abre diálogo de dois campos (Nome/URL).

`MainActivity.kt`: `renderShortcuts()` infla os atalhos salvos; `showAddShortcutDialog()` valida e persiste novos atalhos, normalizando `www.x` → `https://www.x`.

---

## 3. Fase 1 da reformulação de UI (esta sessão)

### 3.1 Nova toolbar superior
**Arquivo novo:** `layout/view_top_toolbar.xml`

- Agora contém **apenas**: botão de voltar (ícone, `ic_back.xml`), campo de URL editável, botão de reload (ícone, `ic_reload.xml`).
- Fundo com sombra leve via `GradientShadowCardView` (`cornerRadiusDp=10`, barra quase reta).
- `Desktop/Mobile`, `Downloads`, `Configurações` saíram da toolbar — foram para a sidebar (abaixo).

### 3.2 Painel lateral (sidebar) de atalhos de função
**Arquivos novos:**
- `layout/view_sidebar.xml`: painel overlay (240dp de largura, `match_parent` de altura por enquanto — redimensionamento fica pra Fase 2), fundo com `GradientShadowCardView`, lista scrollável (`ScrollView`) de atalhos, botão circular "+" no rodapé.
- `layout/view_sidebar_item.xml`: item individual (ícone + rótulo horizontal).
- `widget/SidebarPrefsManager.kt`: enum `SidebarFunction` (DEVICE_MODE, DOWNLOADS, SETTINGS, HISTORY) + persistência de quais estão visíveis, em `SharedPreferences`.
- Ícones novos: `ic_device_mobile.xml`, `ic_device_desktop.xml`, `ic_downloads.xml`, `ic_history.xml`, `ic_settings.xml`, `ic_menu_hamburger.xml`, `ic_back.xml`, `ic_reload.xml`.

**Comportamento:**
- Handle fixo "☰" no canto superior esquerdo do `contentArea` abre o painel (`openSidebar()`); botão "☰" dentro do painel fecha (`closeSidebar()`).
- Botão físico/gesto de voltar fecha a sidebar primeiro, antes de qualquer outra navegação (`onBackPressed()` atualizado).
- Item "Modo desktop/celular" alterna e re-renderiza a sidebar pra atualizar ícone/rótulo.
- Item "Downloads" abre `DownloadsActivity` (existente — será corrigida na Fase 4).
- Item "Configurações" abre `SettingsActivity`.
- Item "Histórico" abre `SettingsActivity` com extra `EXTRA_OPEN_HISTORY=true` — **placeholder**: a tela de histórico em si é a Fase 3, ainda não implementada.
- Botão "+" no rodapé abre `showManageSidebarShortcutsDialog()`: popup com checkbox por função, escolhendo quais aparecem na sidebar.

### 3.3 Barra de busca da Home maior e mais "grossa"
**Arquivo:** `layout/activity_main.xml`

- Trocada de campo fino com `bg_search_field.xml` (shape XML simples) para um `FrameLayout` de **64dp de altura** com `GradientShadowCardView` de fundo (`cornerRadiusDp=32`) — gradiente + sombra leve via Canvas, ícone e texto maiores (24dp / 18sp).

### 3.4 `activity_main.xml` — estrutura geral
- `bottomBar` (barra retangular inferior que abria Configurações) **removida** — função absorvida pelo item "Configurações" da sidebar.
- Estrutura: `navToolbar` (include) → `contentArea` (WebView + Home + sidebar overlay + handle hambúrguer).

---

## 4. O que falta (Fases 2, 3 e 4 — não implementadas nesta sessão)

### Fase 2 — Redimensionamento da sidebar
- Sidebar hoje é `match_parent` de altura, largura fixa 240dp.
- Falta: suporte pra ocupar metade superior, metade inferior, ou tela toda, com o usuário escolhendo o modo de exibição.

### Fase 3 — Tela de Histórico
- `EXTRA_OPEN_HISTORY` já existe como constante em `SettingsActivity`, mas **a seção de histórico em si não foi implementada**.
- Falta: `HistoryManager` (registro de navegação — hoje não existe nenhum registro de histórico no app), layout com círculo/capa do site + barra retangular com nome + subtítulo (URL) recortado, gradiente de sombra, clique abre o site.
- O item "Histórico" na sidebar já está cabeado e funcional assim que a tela existir.

### Fase 4 — Correção da função de excluir arquivo em Downloads
- `DownloadsActivity.kt` existe mas tem o problema apontado: o diálogo de confirmação usa `getString(R.string.settings_downloads) + "?"` como mensagem, o que é confuso/sem sentido.
- Falta: renomear/reescrever o fluxo de exclusão com nome claro, adicionar botão de seta-para-cima (abrir arquivo — `ic_open_file.xml`, já criado) e botão de lixeira (`ic_delete.xml`, já criado) em cada item da lista, com popup de confirmação "Excluir arquivo?".

---

## 5. Arquivos tocados nesta sessão (diff completo)

```
M  app/src/main/AndroidManifest.xml
M  app/src/main/java/com/xaulinxs/aosp/browser/MainActivity.kt
M  app/src/main/java/com/xaulinxs/aosp/browser/SettingsActivity.kt
A  app/src/main/java/com/xaulinxs/aosp/browser/widget/BrowserSearchWidgetProvider.kt
A  app/src/main/java/com/xaulinxs/aosp/browser/widget/GradientShadowCardView.kt
A  app/src/main/java/com/xaulinxs/aosp/browser/widget/SidebarPrefsManager.kt
M  app/src/main/java/com/xaulinxs/funcoes/FileManagerActivity.java
M  app/src/main/java/com/xaulinxs/funcoes/SearchEngineManager.java
A  app/src/main/java/com/xaulinxs/funcoes/ShortcutManager.java
A  app/src/main/res/drawable-night/bg_widget_search_bar.xml
A  app/src/main/res/drawable/bg_shortcut_add_button.xml
A  app/src/main/res/drawable/bg_widget_search_bar.xml
A  app/src/main/res/drawable/ic_add.xml
A  app/src/main/res/drawable/ic_back.xml
A  app/src/main/res/drawable/ic_delete.xml          (pronto para Fase 4)
A  app/src/main/res/drawable/ic_device_desktop.xml
A  app/src/main/res/drawable/ic_device_mobile.xml
A  app/src/main/res/drawable/ic_downloads.xml
A  app/src/main/res/drawable/ic_history.xml
A  app/src/main/res/drawable/ic_menu_hamburger.xml
A  app/src/main/res/drawable/ic_open_file.xml       (pronto para Fase 4)
A  app/src/main/res/drawable/ic_reload.xml
A  app/src/main/res/drawable/ic_settings.xml
M  app/src/main/res/layout/activity_main.xml
A  app/src/main/res/layout/view_shortcut_item.xml
A  app/src/main/res/layout/view_shortcuts_card.xml
A  app/src/main/res/layout/view_sidebar.xml
A  app/src/main/res/layout/view_sidebar_item.xml
A  app/src/main/res/layout/view_top_toolbar.xml
A  app/src/main/res/layout/widget_browser_search.xml
A  app/src/main/res/values/attrs.xml
M  app/src/main/res/values/strings.xml
A  app/src/main/res/xml/widget_browser_search_info.xml
```

## 6. Verificações feitas antes da entrega

- Todos os arquivos XML validados como bem formados (`xml.etree.ElementTree`).
- Todos os IDs referenciados em `MainActivity.kt` conferidos contra os layouts (`findViewById` ↔ `android:id`) — sem divergências.
- Todas as strings (`R.string.*`) referenciadas conferidas contra `strings.xml` — sem divergências.
- Todos os drawables (`@drawable/*`) referenciados conferidos contra os arquivos existentes — sem divergências.
- Chaves `{}` balanceadas em `MainActivity.kt` e `GradientShadowCardView.kt`.
- Confirmado `android.app.AlertDialog` (framework puro) em vez de `androidx.appcompat.app.AlertDialog` em todos os diálogos.

**Não foi possível compilar com `gradlew`** nesta sessão (ambiente sem acesso de rede a `services.gradle.org`) — apenas revisão estática manual, como já vinha sendo o caso nas sessões anteriores. Recomendo compilar localmente no Termux antes de considerar a Fase 1 finalizada.
