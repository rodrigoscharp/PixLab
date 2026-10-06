# ADR-0004 — Motor de caos determinístico por seed

**Status:** Aceita · **Data:** 2026-10-06

## Contexto
Testes com falhas aleatórias são úteis para encontrar bugs, mas inúteis se o bug não puder ser reproduzido. Um teste instável ("flaky") num sistema financeiro é pior que nenhum teste.

## Decisão
- O `psp-simulator` recebe um **perfil de caos** (lista de cenários do catálogo com probabilidades/parâmetros) e uma **seed**.
- Toda decisão aleatória (duplicar? atrasar quanto? em que ordem?) usa um `RandomGenerator` derivado da seed + id do evento.
- O tempo é abstraído por um `Clock` injetável; atrasos longos ("2 horas") são simulados avançando o relógio, não esperando.
- Todo teste que falha imprime a seed e o perfil; `./gradlew chaos --chaos-profile=<p> --seed=<n>` reproduz a execução.
- Perfis ficam versionados em `chaos-profiles/*.yml`.

## Consequências
- ✅ Bugs encontrados por caos viram testes de regressão permanentes.
- ✅ Cenários de horas rodam em segundos no CI.
- ⚠️ Concorrência real (threads) não é 100% determinística; `PIX-DUP-CONC` usa repetição em massa em vez de seed.

## Alternativas consideradas
- **Toxiproxy / Chaos Mesh:** ótimos para falhas de rede, mas não conhecem a semântica Pix (devolução antes do crédito, MED).
- **Aleatoriedade pura:** descobre bugs, mas não os reproduz.
