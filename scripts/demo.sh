#!/usr/bin/env bash
# Demo ao vivo: paga cobranças sob caos (duplicatas, atrasos, perdas, assinatura forjada, Pix sem cobrança,
# valor divergente) e mostra que cada centavo foi creditado exatamente uma vez.
# Requer os dois serviços rodando (ver README) e curl + jq.
set -euo pipefail

PSP=${PSP_URL:-http://localhost:8091}
MERCHANT=${MERCHANT_URL:-http://localhost:8090}
SEED=${SEED:-42}
N=${N:-15}

bold() { printf '\n\033[1m%s\033[0m\n' "$*"; }
json() { curl -sf -H 'Content-Type: application/json' "$@"; }

bold "1. Perfil de caos 'mixed' com seed $SEED"
json -X PUT "$PSP/sim/chaos/profiles/mixed?seed=$SEED" | jq -c '{seed, cenarios: (.scenarios | keys)}'

bold "2. $N cobranças criadas e pagas"
inicio=$(date -u +%Y-%m-%dT%H:%M:%SZ)
for i in $(seq 1 "$N"); do
  txid=$(json -X POST "$MERCHANT/charges" -d "{\"valor\":\"$((10 + i)).00\"}" | jq -r .txid)
  json -X POST "$PSP/sim/cob/$txid/pagamento" > /dev/null
  printf '.'
done
echo

bold "3. Caos injetado pelo PSP"
curl -sf "$PSP/actuator/prometheus" | grep '^psp_chaos_injections_total' | sed -E 's/.*scenario="([^"]+)".* ([0-9.]+)$/  \1: \2/'

bold "4. Relógio do PSP avança: atrasos de horas e retentativas vencem em segundos"
for _ in $(seq 1 20); do
  json -X POST "$PSP/sim/clock/advance" -d '{"duration":"PT15M"}' > /dev/null
  pending=$(json "$PSP/sim/webhook-deliveries" | jq .pending)
  [ "$pending" = 0 ] && break
  sleep 0.3
done
json -X DELETE "$PSP/sim/chaos" > /dev/null || true
echo "  entregas pendentes: $pending"
curl -sf "$MERCHANT/actuator/prometheus" | grep -E '^webhook_(received|duplicate_discarded|rejected)_total' \
  | sed -E 's/\{.*source="([^"]+)".*\}/ (\1)/; s/\{.*reason="([^"]+)".*\}/ (\1)/; s/^/  /'

bold "5. Consulta ativa recupera o que o webhook perdeu"
fim=$(date -u -v+1M +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -d '+1 min' +%Y-%m-%dT%H:%M:%SZ)
json -X POST "$MERCHANT/admin/consulta-ativa" -d "{\"inicio\":\"$inicio\",\"fim\":\"$fim\"}" | jq -c .
sleep 2

bold "6. Conciliação contra o extrato do PSP"
json -X POST "$MERCHANT/recon/runs" -d "{\"inicio\":\"$inicio\",\"fim\":\"$fim\"}" > /dev/null
sleep 2
json -X POST "$MERCHANT/recon/runs" -d "{\"inicio\":\"$inicio\",\"fim\":\"$fim\"}" | jq -c '.summary | {counts, pspTotal, ledgerTotal, fechado}'

bold "7. Ledger"
json "$MERCHANT/ledger/balances" | jq -c .
json "$MERCHANT/admin/inbox" | jq -c '{inbox: .}'
