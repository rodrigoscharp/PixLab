# Como garantir que um Pix seja creditado exatamente uma vez

*Rascunho para LinkedIn / dev.to. Código completo: PixLab.*

Todo sistema que recebe Pix depende de uma notificação assíncrona do PSP: o webhook. Em produção, esse webhook chega duplicado quando o PSP não recebe o `200` a tempo, chega atrasado, chega fora de ordem (a devolução antes do crédito) e às vezes não chega. Mesmo assim, cada centavo precisa ser creditado **exatamente uma vez**, e no fim do dia o saldo interno tem que bater com o extrato do PSP.

Montei um laboratório para provar isso: um PSP falso que injeta essas falhas de propósito, com seed, e um recebedor de referência que precisa sobreviver a todas. Seguem as cinco decisões que fazem a diferença.

## 1. Idempotência é uma constraint, não um `if`

O erro clássico:

```java
if (!pagamentoJaExiste(e2eId)) {   // duas threads passam aqui juntas...
    creditar(e2eId);               // ...e as duas creditam
}
```

Com dois webhooks idênticos chegando no mesmo milissegundo, os dois `SELECT` respondem "não existe" antes de qualquer `INSERT`. A garantia tem que vir do banco:

```sql
insert into webhook_inbox (source, event_key, payload)
values ('PIX', :e2eId, :payload)
on conflict (source, event_key) do nothing
```

O endpoint grava, responde `200` e mais nada. No laboratório, **1.000 entregas simultâneas do mesmo webhook geram exatamente 1 crédito**.

## 2. Responda rápido, processe depois

O PSP reenvia quando o seu `200` demora. Então o endpoint não processa: valida a assinatura, grava na inbox e responde. Workers consomem a inbox com `SELECT … FOR UPDATE SKIP LOCKED`. Vários workers em paralelo, nenhum pegando o mesmo evento, e a transição de estado, o lançamento no ledger e o evento da outbox na **mesma transação**. Num teste de carga com 500 req/s e 30% de reentregas, o p99 do ack ficou em 16 ms.

## 3. Webhook é otimização, não fonte da verdade

Se o webhook nunca chega, quem descobre? Um job consulta periodicamente `GET /pix?inicio&fim`, em janelas que se sobrepõem, e injeta cada Pix **na mesma inbox**. Como a inbox é idempotente, reler o mesmo Pix cem vezes é inofensivo. Existe um único caminho de entrada (webhook, consulta ativa, reparo da conciliação), e é isso que torna a garantia verdadeira em todos os casos.

## 4. Eventos fora de ordem esperam, não quebram

A devolução chegou antes do crédito? O evento volta para a inbox com backoff exponencial até o crédito aparecer. Se nunca aparecer, vai para quarentena, à vista de todos. Nunca fica preso: todo evento termina `PROCESSED` ou `QUARANTINED`. A soma das devoluções nunca passa do valor pago, garantida com lock na linha do pagamento. Um teste de propriedade com jqwik embaralha MED, crédito, falhas e duplicatas em 300 ordens diferentes, e o invariante vale em todas.

## 5. Concilie contra o extrato, e ajuste por lançamento

No fim, o extrato do PSP é a verdade sobre o dinheiro que entrou. Um job Spring Batch, reiniciável, compara extrato × ledger e classifica cada item: `OK`, `FALTA_NO_LEDGER` (reinjeta), `FALTA_NO_PSP` (alerta de crédito fantasma), `VALOR_DIVERGENTE`, `SEM_COBRANCA`, `DUPLICADO`. Divergência de valor não edita o passado: vira um **lançamento de ajuste** contra uma conta de suspense, e um caso para análise. Depois de cada execução, Σ `psp:pix:liquidar` = Σ extrato, em centavos.

## O que o caos encontrou

O mais valioso foram os bugs que eu não teria achado lendo o código:

- **Deadlock no dispatcher.** Ele segurava `FOR UPDATE` enquanto threads de envio tentavam atualizar as mesmas linhas por outra conexão. Corrigi com uma reserva curta (lease) e nenhuma transação aberta durante o HTTP.
- **Extrato sobrescrito.** Reaplicar a mesma seed no mesmo minuto regerou um e2eId, e o `save()` do JPA fez *merge* por cima do Pix antigo em vez de falhar. A correção foi insert estrito, e o mesmo bug apareceu depois no rtrId das devoluções.
- **Referência ambígua no ledger.** O id de uma devolução só é único dentro do Pix. Usar só o id como referência misturava devoluções de Pix diferentes.

Todos foram encontrados por testes com seed, e cada um virou teste de regressão. É esse o argumento do laboratório: se a falha é reproduzível, ela vira teste, e o teste vira garantia.

---

**Para levar:** constraint `UNIQUE` em vez de `SELECT`; ack rápido mais inbox; um único caminho de entrada; eventos fora de ordem com backoff e quarentena; conciliação que corrige por lançamento. E caos determinístico para provar tudo isso.
