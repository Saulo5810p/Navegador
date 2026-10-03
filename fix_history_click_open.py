#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
fix_history_click_open.py

Uso (dentro do repo clonado no Termux):
    cd ~/Navegador
    python3 fix_history_click_open.py
  ou:
    python3 fix_history_click_open.py /caminho/do/Navegador

Problema: tocar num item da tela de Historico nao abria a pagina na WebView.

Causa: a raiz de res/layout/item_history.xml tinha android:clickable="true" e
android:focusable="true". Uma linha de ListView clicavel vira ela mesma o alvo do
toque (o ACTION_UP nunca chega ao ListView), entao o
ListView.setOnItemClickListener de HistoryActivity nunca era chamado.

Correcao:
  1. item_history.xml: remove clickable/focusable da raiz e adiciona
     descendantFocusability="blocksDescendants". O feedback visual
     (selectableItemBackground) continua funcionando, pois e o proprio
     ListView que marca a linha como pressed.
  2. HistoryActivity: ignora entrada sem URL e trata falha ao abrir.

Idempotente.
"""
import os
import re
import sys

MARK = "XAULINXS_FIX_HISTORY_CLICK"

root = sys.argv[1] if len(sys.argv) > 1 else os.getcwd()
MAIN = os.path.join(root, "app/src/main")
ITEM = os.path.join(MAIN, "res/layout/item_history.xml")
ACT = os.path.join(MAIN, "java/com/xaulinxs/aosp/browser/HistoryActivity.kt")

for p in (ITEM, ACT):
    if not os.path.isfile(p):
        sys.exit("ERRO: arquivo nao encontrado: %s\nRode dentro da raiz do repo Navegador." % p)


def read(p):
    with open(p, encoding="utf-8") as f:
        return f.read()


def write(p, s):
    with open(p, "w", encoding="utf-8", newline="\n") as f:
        f.write(s)


# ---------------------------------------------------------------------------
# 1) item_history.xml
# ---------------------------------------------------------------------------
x = read(ITEM)
if MARK in x:
    print("[skip] item_history.xml ja corrigido")
else:
    m = re.search(r"<LinearLayout\b[^>]*>", x)
    if not m:
        sys.exit("ERRO: raiz <LinearLayout> nao encontrada em item_history.xml")
    root_tag = m.group(0)
    new_tag = re.sub(r'\n\s*android:clickable="true"', "", root_tag)
    new_tag = re.sub(r'\n\s*android:focusable="true"', "", new_tag)
    if new_tag == root_tag:
        print("[warn] raiz de item_history.xml nao tinha clickable/focusable")
    if "descendantFocusability" not in new_tag:
        new_tag = new_tag.replace(
            'android:orientation="horizontal"',
            'android:orientation="horizontal"\n    android:descendantFocusability="blocksDescendants"',
            1,
        )
    x = x.replace(root_tag, new_tag, 1)
    x = x.replace("?>\n", "?>\n<!-- " + MARK + ": a raiz nao pode ser clickable/focusable, senao engole o toque\n     e o OnItemClickListener do ListView nunca dispara. -->\n", 1)
    write(ITEM, x)
    print("[ok] item_history.xml: raiz sem clickable/focusable")

# ---------------------------------------------------------------------------
# 2) HistoryActivity.kt
# ---------------------------------------------------------------------------
k = read(ACT)
if MARK in k:
    print("[skip] HistoryActivity ja corrigida")
else:
    old = (
        "            val entry = parent.getItemAtPosition(position) as HistoryEntry\n"
        "            val intent = Intent(this, MainActivity::class.java)\n"
        "            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP\n"
        "            intent.putExtra(EXTRA_OPEN_URL, entry.url)\n"
        "            startActivity(intent)\n"
        "            finish()\n"
    )
    new = (
        "            // " + MARK + "\n"
        "            val entry = parent.getItemAtPosition(position) as? HistoryEntry\n"
        "            val url = entry?.url\n"
        "            if (url.isNullOrBlank()) {\n"
        "                return@setOnItemClickListener\n"
        "            }\n"
        "            val intent = Intent(this, MainActivity::class.java)\n"
        "            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP\n"
        "            intent.putExtra(EXTRA_OPEN_URL, url)\n"
        "            startActivity(intent)\n"
        "            finish()\n"
    )
    if old not in k:
        sys.exit("ERRO: bloco do setOnItemClickListener nao encontrado em HistoryActivity.kt")
    k = k.replace(old, new, 1)
    write(ACT, k)
    print("[ok] HistoryActivity: guarda de URL vazia")

print("\nPronto. Compile com: ./gradlew assembleDebug")
