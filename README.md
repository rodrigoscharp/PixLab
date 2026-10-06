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

Java 21 · Spring Boot 3 · Gradle · PostgreSQL 16 · RabbitMQ · Spring Batch · Flyway · Testcontainers · jqwik · Docker Compose · GitHub Actions

## Status

🚧 Design concluído. Próxima fase: **F0 — Fundação**.

---

## ▶️ Próxima sessão: F0 — Fundação (~8h)

Objetivo: `docker compose up` sobe tudo e o CI fica verde. Nada de regra de negócio ainda.

- [ ] Gerar projeto Gradle multi-módulo (Kotlin DSL) com `settings.gradle.kts` incluindo `psp-simulator`, `merchant-core`, `shared-contracts`
- [ ] Version catalog (`gradle/libs.versions.toml`) com Spring Boot 3, Testcontainers, jqwik
- [ ] Toolchain Java 21 configurada no build
- [ ] `psp-simulator` e `merchant-core` com Spring Boot Web, Actuator, Data JPA, Flyway, PostgreSQL
- [ ] Portas: simulador `8081`, recebedor `8080`
- [ ] `docker-compose.yml`: `postgres-psp` (5433), `postgres-merchant` (5432), `rabbitmq` (5672 / 15672)
- [ ] Migração Flyway `V1__init.sql` vazia em cada serviço
- [ ] Um teste de integração por serviço com Testcontainers subindo o Postgres
- [ ] Workflow `.github/workflows/ci.yml` rodando `./gradlew build`
- [ ] Atualizar o status do F0 em [docs/roadmap.md](docs/roadmap.md)

**Pronto quando:** `./gradlew build` passa localmente e no GitHub Actions, e `/actuator/health` responde `UP` nos dois serviços.

Depois disso vem a **F1 — Pix caminho feliz** (ver [roadmap](docs/roadmap.md#f1--pix-caminho-feliz)).

## Como rodar

> Disponível a partir da F0.

```bash
docker compose up -d
./gradlew :psp-simulator:bootRun
./gradlew :merchant-core:bootRun
```

## Licença

MIT
