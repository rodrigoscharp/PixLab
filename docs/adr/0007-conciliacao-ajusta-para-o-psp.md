# ADR-0007 — Conciliação ajusta o ledger para o valor do PSP

**Status:** Aceita · **Data:** 2026-10-06

## Contexto
O design doc diz que `VALOR_DIVERGENTE` "abre caso para análise manual". Mas se o webhook trouxe um valor diferente do que o PSP de fato liquidou (`PIX-AMOUNT`), a conta `psp:pix:liquidar` fica com o valor errado até alguém analisar, e o invariante 4 (Σ `psp:pix:liquidar` = Σ extrato) não fecha.

## Decisão
- Para valores, o **extrato do PSP é a fonte da verdade**: foi o que de fato entrou.
- Em `VALOR_DIVERGENTE`, a conciliação lança um **ajuste** entre `psp:pix:liquidar` e `suspense:nao_identificado` igual à diferença (PSP − ledger), com referência `recon:<e2eId>`, e publica `recon.valor_divergente` na outbox. A diferença fica em suspense até a análise.
- O ajuste é calculado contra o que o ledger já tem (crédito + ajustes anteriores), então rodar a conciliação de novo **não ajusta duas vezes**. O item continua classificado como `VALOR_DIVERGENTE` (ação `CASO`) enquanto o pagamento registrado divergir.
- A conciliação sempre repassa o Pix completo (crédito e devoluções) pela inbox. É idempotente e repara devoluções cujo aviso se perdeu, além de `FALTA_NO_LEDGER`.

## Consequências
- ✅ Invariante 4 fecha em centavos após cada execução que segue os reparos.
- ✅ Correção por lançamento, nunca por edição: o histórico mostra o valor do webhook, o ajuste e quem o fez.
- ⚠️ Suspense pode ficar com saldo devedor (o webhook disse mais do que entrou); a análise do caso o zera.

## Alternativas consideradas
- **Só abrir caso:** fiel ao texto original, mas o ledger fica errado até a análise manual.
- **Corrigir o pagamento:** edita o passado e esconde a divergência.
