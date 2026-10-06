# ADR-0005 — Java 25 e Spring Boot 4

**Status:** Aceita · **Data:** 2026-10-06

## Contexto
O design doc previa Java 21 e Spring Boot 3. Na F0, o Spring Initializr já não oferece a linha 3.x: o suporte OSS do Spring Boot 3.5 terminou e a versão corrente é a 4.1. O JDK instalado localmente é o 25 (LTS); o 21 não está disponível.

## Decisão
- **Java 25** (LTS) como toolchain de todos os módulos.
- **Spring Boot 4.1** nos dois serviços, com os starters modulares (`spring-boot-starter-webmvc`, `spring-boot-starter-flyway` etc.).
- Versões centralizadas em `gradle/libs.versions.toml`.

## Consequências
- ✅ Stack com suporte ativo durante toda a vida do projeto.
- ✅ Acesso a recursos recentes da linguagem (pattern matching, virtual threads estáveis).
- ⚠️ Parte do material de referência online ainda cita Spring Boot 3; nomes de starters e pacotes de teste mudaram (ex.: `org.springframework.boot.webmvc.test.autoconfigure`).

## Alternativas consideradas
- **Spring Boot 3.5 + Java 21**: fiel ao design original, mas sem suporte OSS gratuito e exigindo instalar outro JDK.
