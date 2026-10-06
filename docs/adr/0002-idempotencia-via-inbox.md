# ADR-0002 — Idempotência via inbox com constraint UNIQUE

**Status:** Aceita · **Data:** 2026-10-06

## Contexto
O PSP reenvia webhooks quando não recebe 2xx a tempo, e pode enviar a mesma notificação em paralelo. A abordagem ingênua — `SELECT` para ver se o e2eId existe e depois `INSERT` — tem uma janela de corrida: duas requisições simultâneas passam pelo `SELECT` e ambas creditam.

## Decisão
1. O endpoint grava cada evento em `webhook_inbox` com `UNIQUE(source, event_key)` usando `INSERT ... ON CONFLICT DO NOTHING`, e responde `200` sem processar.
2. `event_key` = `e2eId` para créditos; `rtrId` (ou `e2eId:id`) para devoluções.
3. Um processador assíncrono consome a inbox (`SELECT ... FOR UPDATE SKIP LOCKED`), aplica a transição de estado, o lançamento no ledger e a outbox **na mesma transação**, e marca o evento como `PROCESSED`.
4. A consulta ativa e a conciliação reinjetam eventos pela mesma inbox — existe um único caminho de entrada.

## Consequências
- ✅ Idempotência garantida pelo banco, inclusive sob concorrência.
- ✅ Ack rápido reduz reentregas causadas por timeout.
- ✅ A inbox vira trilha de auditoria de tudo que chegou.
- ⚠️ Processamento é eventualmente consistente (há lag entre ack e crédito); medir `inbox_lag_seconds`.
- ⚠️ Inbox cresce; precisa de política de retenção/particionamento.

## Alternativas consideradas
- **Lock distribuído (Redis) por e2eId:** mais uma peça de infra e falha se o lock expirar durante o processamento.
- **Idempotência só no ledger (UNIQUE no lançamento):** protege o crédito, mas não os efeitos colaterais (outbox, notificações).
- **Processar dentro da requisição:** aumenta latência e reentregas.
