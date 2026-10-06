# PixLab

Laboratório para testar **idempotência** e **conciliação** de pagamentos via Pix e boleto contra um PSP simulado que injeta falhas reais: webhooks duplicados, atrasados, fora de ordem ou perdidos, devoluções, MED e arquivos CNAB problemáticos.

> Pergunta central: **como garantir que cada centavo seja creditado exatamente uma vez — e provar isso?**

## Componentes

| Módulo | Papel |
|---|---|
| `psp-simulator` | PSP falso inspirado na API Pix do Banco Central, com motor de caos determinístico por seed |
| `merchant-core` | Recebedor de referência: inbox idempotente, máquina de estados, ledger de partida dobrada e conciliação |
| `shared-contracts` | Apenas DTOs e schemas dos payloads |
| `e2e-tests` | Testes de ponta a ponta com os dois serviços rodando como processos |

## Documentação

- [Design doc](docs/design.md) — arquitetura, domínio Pix/boleto, catálogo de cenários de caos, invariantes
- [Roadmap](docs/roadmap.md) — 9 fases com critério de pronto
- [ADRs](docs/adr/) — decisões de arquitetura

## Stack

Java 25 · Spring Boot 4 · Gradle · PostgreSQL 16 · RabbitMQ · Spring Batch · Flyway · Testcontainers · jqwik · Docker Compose · GitHub Actions

## Status

✅ **F0 — Fundação** e **F1 — Pix caminho feliz** concluídas. Próxima fase: **F2 — Idempotência**.

---

## ▶️ Próxima sessão: F2 — Idempotência (~10h)

Objetivo: 1.000 entregas concorrentes do mesmo webhook geram exatamente 1 crédito. Detalhes no [roadmap](docs/roadmap.md#f2--idempotência) e no [ADR-0002](docs/adr/0002-idempotencia-via-inbox.md).

- [ ] Tabela `webhook_inbox` com `UNIQUE(source, event_key)`; endpoint grava com `ON CONFLICT DO NOTHING` e responde `200` sem processar
- [ ] Processador assíncrono da inbox com `FOR UPDATE SKIP LOCKED`, substituindo o processamento síncrono do `PixReceiver`
- [ ] Tabela `outbox` gravada na mesma transação do lançamento + relay para RabbitMQ
- [ ] Cenário `PIX-DUP`: mesmo webhook N vezes em sequência
- [ ] Cenário `PIX-DUP-CONC`: 1.000 entregas concorrentes, exatamente 1 crédito

## Como rodar

Requisitos: JDK 25 e Docker.

```bash
docker compose up -d
./gradlew :psp-simulator:bootRun   # http://localhost:8091/actuator/health
./gradlew :merchant-core:bootRun   # http://localhost:8090/actuator/health
./gradlew build                    # testes sobem Postgres via Testcontainers
```

Portas ocupadas na sua máquina? Sobrescreva por variável de ambiente (vale para Compose e para os serviços):

| Variável | Padrão |
|---|---|
| `MERCHANT_PORT` | 8090 |
| `PSP_PORT` | 8091 |
| `MERCHANT_DB_PORT` | 5435 |
| `PSP_DB_PORT` | 5434 |

RabbitMQ: `5672`, painel em http://localhost:15672 (`pixlab` / `pixlab`).

### Fluxo Pix na mão

Com os dois serviços rodando (o recebedor registra o webhook no PSP ao subir):

```bash
# 1. Recebedor cria a cobrança (e a registra no PSP)
TXID=$(curl -s -X POST localhost:8090/charges -H 'Content-Type: application/json' \
  -d '{"valor":"150.00","descricao":"Pedido #4821"}' | jq -r .txid)

# 2. Pagador simulado paga; o PSP gera o e2eId e dispara o webhook
curl -s -X POST localhost:8091/sim/cob/$TXID/pagamento -H 'Content-Type: application/json' \
  -d '{"infoPagador":"Pedido #4821"}' | jq

# 3. Cobrança concluída e ledger balanceado
curl -s localhost:8090/charges/$TXID | jq
curl -s localhost:8090/ledger/balances | jq
```

| Serviço | Endpoint | Para quê |
|---|---|---|
| psp-simulator | `PUT /cob/{txid}`, `GET /cob/{txid}` | Cobrança imediata (API Pix) |
| psp-simulator | `PUT /webhook/{chave}` | Configura a URL de webhook da chave |
| psp-simulator | `POST /sim/cob/{txid}/pagamento` | Pagador simulado (fora da API Pix) |
| merchant-core | `POST /charges`, `GET /charges/{txid}` | Cria e consulta cobranças |
| merchant-core | `POST /webhook/pix` | Recebe o webhook do PSP |
| merchant-core | `GET /ledger/balances` | Saldo por conta; `total` é sempre `0.00` |

O teste `e2e-tests` faz esse mesmo fluxo subindo os dois `bootJar` como processos separados.

## Licença

MIT
