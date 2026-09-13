#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
corrigir_build_navegador.py

Corrige o erro de build do projeto "Navegador":

    DownloadHandler.java:41: error: cannot find symbol
    query.orderBy(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP, DownloadManager.Query.ORDER_DESCENDING);

O motivo do erro: DownloadManager.Query (API real do Android) NÃO tem
método orderBy() nem constante ORDER_DESCENDING. Essa API nunca existiu
no Android — foi um engano no código original. A ordenação "mais recente
primeiro" precisa ser feita manualmente, depois de montar a lista, usando
o campo timestamp já lido do cursor.

Como usar:
    1. Copie este arquivo para dentro da pasta do projeto (Navegador/).
    2. Rode:  python3 corrigir_build_navegador.py
    3. Depois rode de novo:  ./gradlew assembleDebug

O script é seguro para rodar mais de uma vez: se a correção já foi
aplicada, ele apenas avisa e não mexe em nada de novo. Um backup do
arquivo original é salvo com a extensão .bak na primeira execução.
"""

import sys
from pathlib import Path

# Caminho do arquivo com problema, relativo à raiz do projeto (pasta onde
# este script deve ser executado, dentro de Navegador/).
ALVO = Path("app/src/main/java/com/xaulinxs/funcoes/DownloadHandler.java")

LINHA_QUEBRADA = (
    "        query.orderBy(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP, "
    "DownloadManager.Query.ORDER_DESCENDING);\n"
)

LINHA_CORRIGIDA = (
    "        // DownloadManager.Query nao tem orderBy() nem ORDER_DESCENDING "
    "(essa API nao existe no Android); a ordenacao por mais recente e feita\n"
    "        // manualmente logo abaixo, depois que a lista e montada, usando o "
    "campo timestamp.\n"
)

MARCADOR_RETURN = "        return list;\n    }\n\n    /** Abre um download já concluído"

ORDENACAO = (
    "        Collections.sort(list, (a, b) -> Long.compare(b.timestamp, a.timestamp));\n"
    "        return list;\n    }\n\n    /** Abre um download já concluído"
)

IMPORT_ANTIGO = "import java.util.ArrayList;\n"
IMPORT_NOVO = "import java.util.ArrayList;\nimport java.util.Collections;\n"


def falhar(msg):
    print("\n[ERRO] " + msg)
    print(
        "\nNão consegui aplicar a correção automaticamente porque o arquivo "
        "está diferente do que eu esperava (talvez já tenha sido editado, ou "
        "o script foi rodado na pasta errada)."
    )
    print(f"Confirme que você está rodando este script dentro da pasta 'Navegador' "
          f"e que o arquivo existe em: {ALVO}")
    sys.exit(1)


def main():
    if not ALVO.exists():
        falhar(f"Não encontrei o arquivo {ALVO}.")

    texto = ALVO.read_text(encoding="utf-8")

    ja_corrigido = "Collections.sort(list" in texto and LINHA_QUEBRADA not in texto

    if ja_corrigido:
        print("[OK] Este arquivo já está corrigido. Nada a fazer.")
        print("Pode rodar: ./gradlew assembleDebug")
        return

    if LINHA_QUEBRADA not in texto:
        falhar(
            "Não encontrei a linha exata do erro (query.orderBy(...ORDER_DESCENDING)) "
            "dentro do arquivo."
        )

    if MARCADOR_RETURN not in texto:
        falhar("Não encontrei o ponto certo para inserir a ordenação da lista.")

    # Backup do arquivo original, só na primeira vez.
    backup = ALVO.with_suffix(ALVO.suffix + ".bak")
    if not backup.exists():
        backup.write_text(texto, encoding="utf-8")
        print(f"[OK] Backup salvo em: {backup}")

    novo_texto = texto.replace(LINHA_QUEBRADA, LINHA_CORRIGIDA, 1)
    novo_texto = novo_texto.replace(MARCADOR_RETURN, ORDENACAO, 1)

    if IMPORT_NOVO not in novo_texto:
        if IMPORT_ANTIGO not in novo_texto:
            falhar("Não encontrei onde adicionar o import de java.util.Collections.")
        novo_texto = novo_texto.replace(IMPORT_ANTIGO, IMPORT_NOVO, 1)

    ALVO.write_text(novo_texto, encoding="utf-8")

    print(f"[OK] Corrigido: {ALVO}")
    print(
        "\nO que foi mudado:\n"
        "  - Removida a chamada inválida query.orderBy(...ORDER_DESCENDING), que\n"
        "    não existe na API do Android e por isso quebrava a compilação.\n"
        "  - Adicionada a ordenação da lista de downloads (mais recente primeiro)\n"
        "    em memória, com Collections.sort, usando o timestamp já lido.\n"
        "  - Adicionado o import de java.util.Collections.\n"
    )
    print("Agora rode de novo:  ./gradlew assembleDebug")


if __name__ == "__main__":
    main()
