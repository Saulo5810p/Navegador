# XaulinXs AOSP Browser

Browser Android que embute um WebView AOSP específico (Android 14,
113.0.5672.136 / UP1A.231005.007.10754064) dentro do próprio apk, via
[WebViewUpgrade](https://github.com/JonaNorman/WebViewUpgrade), em vez de
usar o WebView comercial instalado no sistema (Samsung A35 / One UI).

## Antes de compilar

O apk do WebView **não** vem neste zip. Copie o arquivo que você já
baixou para:

```
app/src/main/assets/aosp_webview.apk
```

(pode apagar o `LEIA-ME.txt` que está nessa pasta depois de colocar o apk).

**Importante sobre arquitetura:** o apk precisa bater com a arquitetura
do seu A35 (normalmente `arm64-v8a`). Se você baixou um apk multi-arch
(universal), não precisa fazer nada extra; se baixou um apk de uma
arquitetura só, confirme que é `arm64-v8a`.

## Compilando no Termux

```bash
cd ~/Browser
chmod +x gradlew
./gradlew assembleDebug
```

O apk final fica em:

```
app/build/outputs/apk/debug/app-debug.apk
```

Instale normalmente com `adb install` ou copiando pro celular.

## Sobre a lib WebViewUpgrade

O código-fonte da lib (`com.norman.webviewup.lib`) está vendorizado direto
em `app/src/main/java/com/norman/webviewup/` - **não é mais** uma
dependência do Maven Central. Motivo: a versão publicada lá (`0.1.0`) é
mais antiga que o código-fonte atual do GitHub em dois pontos que nos
afetaram diretamente:

1. Não tinha o construtor de 2 parâmetros de `UpgradeAssetSource` (erro de
   compilação "No value passed for parameter 'p2'").
2. Não marcava o apk copiado como somente-leitura depois de copiar
   (`SecurityException: Writable dex file ... is not allowed`), exigência
   do Android 14 (targetSdk 34) - ["Safer dynamic code
   loading"](https://developer.android.com/about/versions/14/behavior-changes-14#safer-dynamic-code-loading).

Se um dia sair uma versão nova publicada no Maven Central com essas
correções, dá pra voltar a usar a dependência normal e apagar a pasta
vendorizada.

## O que o app faz

- `BrowserApplication.onCreate()` chama `WebViewUpgrade.upgrade(...)`
  passando o apk embutido em `assets/aosp_webview.apk` **antes** de
  qualquer `WebView` ser criada (exigência da própria lib).
- `MainActivity` só cria a `WebView` depois que o upgrade termina
  (sucesso ou falha) - evita o app criar a WebView cedo demais e
  acabar preso no WebView antigo do sistema pro resto do processo.
- A barra inferior (estilo QSB do AOSP) mostra a versão do Chromium
  lida direto do `UserAgent` (o mesmo dado que o app "WebView Browser
  Tester" mostra) mais o nome do pacote em uso. Se aparecer
  `113.0.5672.136`, é o kernel embutido; qualquer outro número é o
  WebView comercial do sistema.

## Estrutura

```
Browser/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/            <- coloque aosp_webview.apk aqui
│       ├── java/com/xaulinxs/aosp/browser/
│       │   ├── BrowserApplication.kt
│       │   └── MainActivity.kt
│       └── res/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradlew / gradlew.bat
└── gradle/wrapper/
```

Compilação: Gradle 9.6.1 + AGP 8.7.0, 100% Kotlin, sem drawables
customizados (interface só com componentes nativos do Android).
# Navegador
