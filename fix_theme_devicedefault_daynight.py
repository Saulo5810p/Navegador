#!/usr/bin/env python3
"""
fix_theme_devicedefault_daynight.py

Corrige o erro de build:
  ERROR: AAPT: error: resource android:style/Theme.DeviceDefault.DayNight.NoActionBar not found.

Causa: "Theme.DeviceDefault.DayNight" não existe como estilo público do
framework Android - só a família Theme.Material tem a variante DayNight
embutida. O tema anterior (themes.xml) tentava usar um recurso que
simplesmente não existe.

Correção: como o projeto não usa AppCompat (removido na fase 2), o
suporte a claro/escuro é feito à moda antiga do Android - dois arquivos
com o MESMO nome de tema (Theme.AospBrowser):
  - res/values/themes.xml       -> parent Theme.DeviceDefault.Light.NoActionBar (claro)
  - res/values-night/themes.xml -> parent Theme.DeviceDefault.NoActionBar (escuro)
O sistema de recursos do Android escolhe automaticamente qual dos dois
usar conforme o modo claro/escuro atual (controlado por
UiModeManager.setApplicationNightMode, ver ThemeManager.kt).

Rodar de dentro da pasta raiz do projeto (a que contém app/):
  python fix_theme_devicedefault_daynight.py
"""
import os

PROJECT_ROOT = os.path.dirname(os.path.abspath(__file__))

VALUES_THEMES_PATH = os.path.join(
    PROJECT_ROOT, "app", "src", "main", "res", "values", "themes.xml"
)
VALUES_NIGHT_DIR = os.path.join(
    PROJECT_ROOT, "app", "src", "main", "res", "values-night"
)
VALUES_NIGHT_THEMES_PATH = os.path.join(VALUES_NIGHT_DIR, "themes.xml")

LIGHT_THEME_CONTENT = """<resources xmlns:tools="http://schemas.android.com/tools">
    <!-- Versão CLARA (values/, usada quando o modo é claro ou "sistema"
         com o dispositivo em claro): Theme.DeviceDefault.Light.NoActionBar
         é a variante clara real do tema do dispositivo/Android 14.

         "Theme.DeviceDefault.DayNight" NÃO existe como estilo público do
         framework (só Theme.Material tem a variante DayNight embutida) -
         era isso que quebrava o build com "resource ... not found". Sem
         AppCompat, o DayNight é feito à moda antiga do Android: o MESMO
         nome de tema (Theme.AospBrowser) é declarado aqui com base clara
         e de novo em values-night/themes.xml com base escura
         (Theme.DeviceDefault.NoActionBar) - o sistema de recursos escolhe
         automaticamente qual das duas versões usar conforme o modo
         claro/escuro atual (ver ThemeManager, que troca esse modo via
         UiModeManager.setApplicationNightMode). -->
    <style name="Theme.AospBrowser" parent="android:Theme.DeviceDefault.Light.NoActionBar" />
</resources>
"""

DARK_THEME_CONTENT = """<resources xmlns:tools="http://schemas.android.com/tools">
    <!-- Versão ESCURA (values-night/, escolhida automaticamente pelo
         sistema quando o modo escuro está ativo - ver ThemeManager):
         Theme.DeviceDefault.NoActionBar é a variante escura nativa do
         tema do dispositivo/Android 14, sem depender de AppCompat. -->
    <style name="Theme.AospBrowser" parent="android:Theme.DeviceDefault.NoActionBar" />
</resources>
"""


def write_file(path: str, content: str) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)
    print(f"[ok] escrito: {os.path.relpath(path, PROJECT_ROOT)}")


def main() -> None:
    if not os.path.isdir(os.path.join(PROJECT_ROOT, "app")):
        print(
            "[erro] pasta 'app' não encontrada aqui - rode este script de "
            "dentro da raiz do projeto Navegador (a pasta que contém app/)."
        )
        raise SystemExit(1)

    write_file(VALUES_THEMES_PATH, LIGHT_THEME_CONTENT)
    write_file(VALUES_NIGHT_THEMES_PATH, DARK_THEME_CONTENT)

    print(
        "\nPronto. O tema Theme.AospBrowser agora aponta para um recurso "
        "real do framework em ambas as variantes (claro/escuro).\n"
        "Rode de novo: ./gradlew clean assembleDebug"
    )


if __name__ == "__main__":
    main()
