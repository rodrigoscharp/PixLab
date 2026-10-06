# ADR-0006 — Webhook com HMAC e Pix sem cobrança em suspense

**Status:** Aceita · **Data:** 2026-10-06

## Contexto
Duas perguntas ficaram em aberto no design doc ao chegar na F3:

1. **Autenticação do webhook.** A API Pix real usa mTLS. Gerar e distribuir certificados complica o setup local e o CI, e o que o laboratório quer provar é que um webhook não autêntico é rejeitado *antes de qualquer efeito*.
2. **`PIX-UNKNOWN`.** O catálogo pede "quarentena, sem crédito silencioso". Mas um Pix para um `txid` desconhecido é dinheiro que de fato entrou na conta do PSP. Se ele ficar só em quarentena, o invariante 4 (Σ `psp:pix:liquidar` = Σ extrato) não fecha, e a conciliação acusaria `FALTA_NO_LEDGER` e o reinjetaria para sempre.

## Decisão
- O PSP assina o corpo do webhook com **HMAC-SHA256** (`X-Pixlab-Signature: sha256=<hex>`), com segredo compartilhado (`pixlab.webhook.secret`). Um filtro no recebedor verifica em tempo constante e responde `401` antes da inbox. mTLS fica documentado como o equivalente de produção.
- Pix sem cobrança é **lançado**: débito em `psp:pix:liquidar`, crédito em `suspense:nao_identificado`, pagamento sem `charge_id` e evento `pix.sem_cobranca` na outbox. Não é silencioso (há alerta e conta própria) e o ledger continua batendo com o extrato. A conciliação o classifica como `SEM_COBRANCA`.
- Quarentena fica reservada para eventos que **não dá para aplicar** (transição inválida, tentativas esgotadas).

## Consequências
- ✅ Setup local e CI sem PKI; `PIX-BAD-AUTH` testável com uma entrega forjada.
- ✅ Invariante 4 vale também com `PIX-UNKNOWN`.
- ⚠️ Segredo compartilhado precisa de rotação em produção; mTLS não tem esse problema.
- ⚠️ Suspense precisa de rotina operacional (devolver ou identificar o pagador).

## Alternativas consideradas
- **mTLS completo:** fiel à API real, mas cada ambiente precisaria de CA e certificados.
- **Quarentena sem lançamento:** atende ao texto do catálogo, mas quebra o fechamento contábil.
