# Roteiro de 5 minutos para entrevista

## 0:00 — O problema (30 s)
"Todo sistema que recebe Pix depende de webhook do PSP, e webhook chega duplicado, atrasado, fora de ordem ou não chega. O PixLab é um laboratório que prova que cada centavo é creditado exatamente uma vez mesmo assim, e que o saldo bate com o extrato."

## 0:30 — A arquitetura (1 min)
- Dois serviços: um **PSP simulado** com motor de caos determinístico e um **recebedor de referência**.
- **Um único caminho de entrada:** webhook, consulta ativa, reparo da conciliação e arquivo CNAB passam pela mesma inbox com `UNIQUE(source, event_key)`.
- Workers com `FOR UPDATE SKIP LOCKED`; estado, ledger de partida dupla e outbox na mesma transação; outbox publicada no RabbitMQ.

## 1:30 — A demo (1 min)
Rodar `./scripts/demo.sh` (ou mostrar o GIF): perfil de caos com seed, 15 pagamentos, duplicatas descartadas, assinaturas forjadas rejeitadas, consulta ativa recuperando o perdido, conciliação fechando em centavos, inbox sem nada pendente.

## 2:30 — Como eu sei que funciona (1 min)
- Cinco invariantes do design doc, verificados por **jqwik** (500 execuções de caos de entrega, 300 de ordens de devolução) e por E2E com os dois serviços como processos.
- **Caos reproduzível:** `./gradlew chaos --chaos-profile=mixed --seed=42`. Toda falha imprime a seed.
- 1.000 entregas concorrentes geram 1 crédito; o mesmo arquivo CNAB 10 vezes não muda o ledger.

## 3:30 — Um bug que o caos achou (45 s)
"O dispatcher segurava `FOR UPDATE` e as threads de envio tentavam atualizar as mesmas linhas por outra conexão: deadlock silencioso. Troquei por um lease curto, sem transação durante o HTTP. Outro: reaplicar a mesma seed regerava um e2eId e o `save()` do JPA sobrescrevia o Pix antigo. A correção foi insert estrito."

## 4:15 — Produção (45 s)
- Métricas de domínio (`ledger_imbalance` sempre 0, lag da inbox, duplicatas descartadas) no Grafana.
- **Um trace do pagamento ao ledger**, com o `traceparent` gravado na fila para atravessar threads.
- Carga: 500 req/s, 30% de reentregas, p99 de 16 ms, zero erros.

## Perguntas prováveis
| Pergunta | Resposta curta |
|---|---|
| Por que não Kafka? | O que importa é a transação local com outbox; Kafka + Debezium seria a evolução natural ([ADR-0003](adr/0003-postgres-e-rabbitmq.md)). |
| E se o banco cair entre gravar e responder? | O PSP não recebe `200` e reenvia; a constraint descarta se já tinha gravado. |
| Por que HMAC e não mTLS? | Para o laboratório o ponto é rejeitar antes de qualquer efeito; mTLS é o equivalente em produção ([ADR-0006](adr/0006-hmac-e-pix-sem-cobranca.md)). |
| Divergência de valor: quem está certo? | O extrato do PSP. A conciliação ajusta por lançamento e abre caso ([ADR-0007](adr/0007-conciliacao-ajusta-para-o-psp.md)). |
| Boleto pago a menor? | Fica divergente em suspense; a maior conclui e o excedente vira crédito a devolver ([ADR-0008](adr/0008-politica-de-valores-do-boleto.md)). |
