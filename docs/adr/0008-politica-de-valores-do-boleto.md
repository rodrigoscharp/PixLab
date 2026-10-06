# ADR-0008 — Política de valores do boleto

**Status:** Aceita · **Data:** 2026-10-06

## Contexto
O banco liquida o que o pagador pagou, não o que a cobrança pedia. O design doc deixou em aberto o que fazer com pagamento a menor e, por extensão, com pagamento a maior, em dobro e atrasado.

## Decisão
O valor **devido** na data do pagamento é recalculado: original + multa (uma vez, % do original) + juros pro rata dia (% ao mês, mês de 30 dias), arredondados ao centavo. Comparando o pago com o devido:

| Caso | Cobrança | Lançamentos (débito sempre em `banco:boleto:liquidar`) |
|---|---|---|
| Pago = devido (`BOL-LATE` incluso) | `CONCLUIDA` | crédito em `merchant:receita` |
| Pago > devido (`BOL-OVER`) | `CONCLUIDA` | receita = devido; excedente em `merchant:credito_a_devolver` |
| Pago < devido (`BOL-UNDER`) | `DIVERGENTE` | tudo em `suspense:nao_identificado`, para análise |
| Cobrança já paga (`BOL-DOUBLE`) | não muda | tudo em `merchant:credito_a_devolver` |
| Título baixado e pago depois | `DIVERGENTE` | tudo em suspense |
| Nosso número desconhecido | — | tudo em suspense |

Idempotência do retorno CNAB em duas camadas: **por arquivo** (sha256 do conteúdo, `return_file`) e **por registro** (chave `nossoNumero:idOcorrencia` na inbox). Linha inválida descarta só o par T+U afetado. Um arquivo corrigido recupera o resto sem duplicar o que já entrou.

## Consequências
- ✅ Nenhum centavo fica fora do ledger: o banco liquidou, o ledger registra, sempre.
- ✅ A menor nunca vira receita automaticamente; não existe "baixa parcial" silenciosa.
- ⚠️ Crédito a devolver e suspense precisam de rotina operacional (devolução ao pagador, contato).

## Alternativas consideradas
- **Aceitar pagamento a menor como parcial:** comum em alguns negócios, mas esconde inadimplência e complica o "pago" da cobrança.
- **Rejeitar o excedente:** o banco já liquidou; não há o que rejeitar, só devolver.
