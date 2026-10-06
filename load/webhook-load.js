// Carga de webhooks no merchant-core: Pix novos e 30% de reentregas (duplicatas), todos assinados com HMAC.
// docker run --rm -i --add-host host.docker.internal:host-gateway -v "$PWD/load:/scripts" grafana/k6 \
//   run /scripts/webhook-load.js -e BASE_URL=http://host.docker.internal:8090
import http from 'k6/http';
import crypto from 'k6/crypto';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8090';
const SECRET = __ENV.WEBHOOK_SECRET || 'pixlab-dev-secret';
const DUP_RATIO = Number(__ENV.DUP_RATIO || 0.3);
const RUN = Date.now().toString(36).slice(-4);

const duplicates = new Counter('webhook_duplicates_sent');

export const options = {
  scenarios: {
    webhooks: {
      executor: 'ramping-arrival-rate',
      startRate: 50,
      timeUnit: '1s',
      preAllocatedVUs: 50,
      maxVUs: 200,
      stages: [
        { target: 500, duration: '20s' },
        { target: 500, duration: '30s' },
        { target: 0, duration: '5s' },
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.001'],
    http_req_duration: ['p(99)<250'],
  },
};

function e2eId(n) {
  const suffix = (RUN + n.toString(36)).padStart(11, '0').slice(-11);
  return `E12345678202610061530${suffix}`;
}

let sent = 0;

export default function () {
  const n = __VU * 1_000_000 + sent++;
  const replay = sent > 1 && Math.random() < DUP_RATIO;
  if (replay) duplicates.add(1);
  const id = e2eId(replay ? n - 1 : n);
  const body = JSON.stringify({
    pix: [{ endToEndId: id, txid: `carga${id.slice(-20)}`, valor: '10.00', horario: new Date().toISOString(), devolucoes: [] }],
  });
  const res = http.post(`${BASE_URL}/webhook/pix`, body, {
    headers: {
      'Content-Type': 'application/json',
      'X-Pixlab-Signature': `sha256=${crypto.hmac('sha256', SECRET, body, 'hex')}`,
    },
  });
  check(res, { '200': (r) => r.status === 200 });
}
