# PixLab — Roadmap

Cada fase termina com algo **demonstrável** e um teste que prova o que foi feito. Estimativas em horas de foco; no ritmo de ~8h/semana, o projeto completo (~100h) leva cerca de 13 semanas.

| Fase | Entrega | Horas | Status |
|---|---|---|---|
| F0 | Fundação | 8 | ⬜ |
| F1 | Pix caminho feliz | 12 | ⬜ |
| F2 | Idempotência | 10 | ⬜ |
| F3 | Caos de entrega | 14 | ⬜ |
| F4 | Devoluções e MED | 10 | ⬜ |
| F5 | Conciliação Pix | 14 | ⬜ |
| F6 | Boleto + CNAB 240 | 18 | ⬜ |
| F7 | Observabilidade | 8 | ⬜ |
| F8 | Vitrine de portfólio | 8 | ⬜ |

---

## F0 — Fundação
- Gradle multi-módulo: `psp-simulator`, `merchant-core`, `shared-contracts`.
- Docker Compose: 2× Postgres, RabbitMQ.
- Flyway nas duas aplicações.
- GitHub Actions: build + testes com Testcontainers.
- **Pronto quando:** `docker compose up` sobe tudo e o CI está verde.

## F1 — Pix caminho feliz
- Simulador: `PUT /cob/{txid}`, `GET /cob/{txid}`, endpoint para "pagar" uma cobrança, geração de e2eId válido, extrato interno.
- Dispatcher de webhook (sem caos ainda).
- Recebedor: criação de cobrança, endpoint de webhook, máquina de estados, ledger.
- **Pronto quando:** teste end-to-end cria cobrança → paga → ledger balanceado e cobrança `CONCLUIDA`.

## F2 — Idempotência
- Inbox com `UNIQUE(source, event_key)` e ack rápido ([ADR-0002](adr/0002-idempotencia-via-inbox.md)).
- Processador com `FOR UPDATE SKIP LOCKED`.
- Outbox + relay para RabbitMQ.
- **Cenários:** `PIX-DUP`, `PIX-DUP-CONC`.
- **Pronto quando:** 1.000 entregas concorrentes do mesmo webhook geram exatamente 1 crédito.

## F3 — Caos de entrega
- Motor de caos com seed e `Clock` injetável ([ADR-0004](adr/0004-caos-deterministico.md)).
- Retentativa com backoff exponencial no dispatcher.
- Consulta ativa (`GET /pix?inicio&fim`) reinjetando na inbox.
- Quarentena para eventos sem cobrança.
- **Cenários:** `PIX-DELAY`, `PIX-LOST`, `PIX-RETRY`, `PIX-UNKNOWN`, `PIX-BAD-AUTH`.
- **Pronto quando:** invariantes 1, 2 e 5 passam em 500 execuções jqwik com perfis de caos aleatórios.

## F4 — Devoluções e MED
- Simulador: `PUT /pix/{e2eId}/devolucao/{id}` com status assíncrono.
- Recebedor: devolução parcial/total, estorno de lançamento quando `NAO_REALIZADO`, MED como devolução não solicitada.
- **Cenários:** `PIX-OOO`, `PIX-REFUND-PARTIAL`, `PIX-REFUND-FAIL`, `PIX-MED`.
- **Pronto quando:** invariante 3 se mantém sob qualquer ordem de eventos.

## F5 — Conciliação Pix
- Job Spring Batch por janela de tempo, *restartable*.
- Classificação `OK / FALTA_NO_LEDGER / FALTA_NO_PSP / VALOR_DIVERGENTE / SEM_COBRANCA / DUPLICADO`.
- Auto-reparo para `FALTA_NO_LEDGER`; relatório para o resto.
- **Cenário:** `PIX-AMOUNT` + todos os anteriores combinados.
- **Pronto quando:** invariante 4 fecha em centavos após cada execução.

## F6 — Boleto + CNAB 240
- Emissão de boleto: nosso número, código de barras e linha digitável com DV (módulo 10/11).
- Simulador gera arquivo de retorno CNAB 240 (segmentos T/U) por dia.
- Recebedor: parser CNAB, idempotência por arquivo (sha256) e por registro, conciliação de boleto.
- ADR: política para pagamento a menor/maior.
- **Cenários:** `BOL-OVER`, `BOL-UNDER`, `BOL-DOUBLE`, `BOL-LATE`, `BOL-FILE-DUP`, `BOL-FILE-CORRUPT`.
- **Pronto quando:** reprocessar o mesmo arquivo 10× não muda o ledger.

## F7 — Observabilidade
- Micrometer + Prometheus + Grafana via Compose.
- Traces OpenTelemetry do pagamento simulado ao lançamento.
- Dashboard com contadores por cenário e `ledger_imbalance` (sempre 0).
- Teste de carga (Gatling ou k6) com números publicados no README.

## F8 — Vitrine de portfólio
- README em inglês com diagrama, GIF da demo e tabela "cenário → como foi resolvido".
- Artigo técnico (LinkedIn/dev.to): *"Como garantir que um Pix seja creditado exatamente uma vez"*.
- Vídeo curto rodando um cenário de caos ao vivo.
- Roteiro de 5 minutos para explicar o projeto em entrevista.

---

## O que cada fase prova numa entrevista de banco

| Pergunta típica | Fase que responde |
|---|---|
| "Como você garante idempotência?" | F2 |
| "E se o webhook não chegar?" | F3 |
| "Como lida com eventos fora de ordem?" | F4 |
| "Como você sabe que o saldo está certo?" | F5 |
| "Já trabalhou com CNAB/arquivos bancários?" | F6 |
| "Como monitora isso em produção?" | F7 |
| "Como testa sistemas distribuídos?" | F3 (seed + jqwik) |

## Fora do escopo (ideias futuras)
- Pix Automático e Pix por aproximação.
- Kafka + Debezium no lugar do relay de outbox.
- Trocar o simulador por um sandbox real de PSP.
- Multi-tenant (vários recebedores no mesmo simulador).
