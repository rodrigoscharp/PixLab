# ADR-0003 — PostgreSQL + RabbitMQ com outbox

**Status:** Aceita · **Data:** 2026-10-06

## Contexto
O ledger exige transações ACID e constraints fortes. Eventos de domínio (pagamento confirmado, devolução concluída) precisam ser publicados para consumidores downstream sem o problema de *dual write* (gravar no banco e publicar na fila sem atomicidade).

## Decisão
- **PostgreSQL 16** como fonte da verdade (ledger, inbox, outbox, conciliação).
- **RabbitMQ** para eventos de domínio, alimentado pelo padrão **Transactional Outbox**: o evento é gravado na tabela `outbox` na mesma transação do lançamento, e um relay publica e marca como enviado.
- DLQ para mensagens que falham repetidamente.

## Consequências
- ✅ Mesma stack do WalletCore: reaproveita conhecimento e código de ledger.
- ✅ Nenhum evento é publicado sem o lançamento correspondente (e vice-versa).
- ⚠️ Entrega *at-least-once* para downstream: consumidores também precisam ser idempotentes.

## Alternativas consideradas
- **Kafka:** melhor para replay e alto volume, mas pesado para o escopo; pode entrar numa fase futura como experimento.
- **Debezium (CDC) em vez de relay:** elegante, mas adiciona infraestrutura; candidato para a fase de observabilidade.
