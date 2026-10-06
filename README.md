# PixLab

Laboratório para testar **idempotência** e **conciliação** de pagamentos via Pix e boleto contra um PSP simulado que injeta falhas reais: webhooks duplicados, atrasados, fora de ordem ou perdidos, devoluções, MED e arquivos CNAB problemáticos.

> Pergunta central: **como garantir que cada centavo seja creditado exatamente uma vez — e provar isso?**

## Componentes

| Módulo | Papel |
|---|---|
| `psp-simulator` | PSP falso inspirado na API Pix do Banco Central, com motor de caos determinístico por seed |
| `merchant-core` | Recebedor de referência: inbox idempotente, máquina de estados, ledger de partida dobrada e conciliação |
| `shared-contracts` | Apenas DTOs e schemas dos payloads |

## Documentação

- [Design doc](docs/design.md) — arquitetura, domínio Pix/boleto, catálogo de cenários de caos, invariantes
- [Roadmap](docs/roadmap.md) — 9 fases com critério de pronto
- [ADRs](docs/adr/) — decisões de arquitetura

## Stack

Java 25 · Spring Boot 4 · Gradle · PostgreSQL 16 · RabbitMQ · Spring Batch · Flyway · Testcontainers · jqwik · Docker Compose · GitHub Actions

## Status

✅ **F0 — Fundação** concluída. Próxima fase: **F1 — Pix caminho feliz**.

---

## ▶️ Próxima sessão: F1 — Pix caminho feliz (~12h)

Objetivo: cobrança criada, paga e creditada uma vez, sem caos ainda. Detalhes no [roadmap](docs/roadmap.md#f1--pix-caminho-feliz).

- [ ] Simulador: `PUT /cob/{txid}` e `GET /cob/{txid}`
- [ ] Simulador: endpoint para "pagar" uma cobrança, com geração de e2eId válido e extrato interno
- [ ] Simulador: dispatcher de webhook (sem caos)
- [ ] Recebedor: criação de cobrança e endpoint de webhook
- [ ] Recebedor: máquina de estados da cobrança
- [ ] Recebedor: ledger de partida dobrada
- [ ] Teste end-to-end: cria cobrança, paga, ledger balanceado e cobrança `CONCLUIDA`

## Como rodar

Requisitos: JDK 25 e Docker.

```bash
docker compose up -d
./gradlew :psp-simulator:bootRun   # http://localhost:8081/actuator/health
./gradlew :merchant-core:bootRun   # http://localhost:8080/actuator/health
./gradlew build                    # testes sobem Postgres via Testcontainers
```

Portas ocupadas na sua máquina? Sobrescreva por variável de ambiente (vale para Compose e para os serviços):

| Variável | Padrão |
|---|---|
| `MERCHANT_PORT` | 8080 |
| `PSP_PORT` | 8081 |
| `MERCHANT_DB_PORT` | 5432 |
| `PSP_DB_PORT` | 5433 |

RabbitMQ: `5672`, painel em http://localhost:15672 (`pixlab` / `pixlab`).

## Licença

MIT
