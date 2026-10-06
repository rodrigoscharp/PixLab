# Teste de carga — webhooks

Script: [`load/webhook-load.js`](../load/webhook-load.js) (k6). Envia webhooks Pix assinados com HMAC ao `merchant-core`, com **30% de reentregas** do mesmo e2eId, numa rampa até **500 req/s** sustentados por 30 s.

## Resultado (2026-10-06)

Ambiente: Apple M4 (10 núcleos, 16 GB), `merchant-core` em `java -jar` (Java 25, threads virtuais), PostgreSQL 16 e RabbitMQ em Docker Desktop na mesma máquina, 4 workers na inbox.

| Métrica | Valor |
|---|---|
| Requisições | 21.750 em 55 s (média 395 req/s, pico 500 req/s) |
| Reentregas (duplicatas enviadas) | 6.540 |
| Falhas HTTP | 0 |
| Latência do ack — mediana / p95 / p99 | 0,78 ms / 2,1 ms / 16,5 ms |
| Maior lag da inbox (`max_over_time(inbox_lag_seconds[3m])`) | 0,37 s |
| Eventos pendentes ao fim | 0 |
| `ledger_imbalance` / transações desbalanceadas | 0 / 0 |

O ack rápido (gravar na inbox e responder) mantém a latência baixa independentemente do processamento. Duplicatas custam um `INSERT ... ON CONFLICT DO NOTHING` e são descartadas pela constraint `UNIQUE`, sem tocar no ledger.

## Como reproduzir

```bash
docker compose --profile observability up -d
./gradlew :psp-simulator:bootJar :merchant-core:bootJar
java -jar psp-simulator/build/libs/psp-simulator-0.0.1-SNAPSHOT.jar &
java -jar merchant-core/build/libs/merchant-core-0.0.1-SNAPSHOT.jar &

docker run --rm -i --add-host host.docker.internal:host-gateway -v "$PWD/load:/scripts" grafana/k6 \
  run /scripts/webhook-load.js -e BASE_URL=http://host.docker.internal:8090
```

Acompanhe no Grafana (http://localhost:3001, dashboard *PixLab*): eventos novos × duplicatas descartadas, lag da inbox e `ledger_imbalance`.
