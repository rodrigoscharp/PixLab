# ADR-0001 — Simulador e recebedor como serviços separados

**Status:** Aceita · **Data:** 2026-10-06

## Contexto
O laboratório precisa reproduzir a relação real entre um PSP e um recebedor: rede no meio, falhas de entrega, retentativas e relógios diferentes. Se os dois lados rodarem no mesmo processo, chamadas viram invocações de método e as falhas mais interessantes (timeout, reentrega, corrida) desaparecem.

## Decisão
Dois serviços Spring Boot independentes — `psp-simulator` e `merchant-core` — num build Gradle multi-módulo, com um módulo `shared-contracts` contendo **apenas** DTOs e o schema dos payloads. Cada serviço tem seu próprio banco.

## Consequências
- ✅ Falhas de rede e HTTP são reais (timeouts, 5xx, conexões recusadas).
- ✅ O `merchant-core` pode ser lido isoladamente como "implementação de referência".
- ✅ Dá para trocar o simulador por um sandbox real de PSP no futuro.
- ⚠️ Setup local exige Docker Compose e dois bancos.
- ⚠️ `shared-contracts` não pode virar um "módulo de domínio compartilhado"; só contratos.

## Alternativas consideradas
- **Monólito com módulos:** mais simples, mas esconde exatamente os problemas que o lab quer expor.
- **WireMock como PSP:** bom para stubs, ruim para estado (extrato, devoluções, retentativa com backoff).
