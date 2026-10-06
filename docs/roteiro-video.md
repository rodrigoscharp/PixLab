# Roteiro do vídeo curto (≈ 90 s)

Gravação de tela com áudio. Deixe os serviços rodando (`docker compose --profile observability up -d` e os dois `bootRun`) e o Grafana aberto em http://localhost:3001.

| Tempo | Tela | Fala |
|---|---|---|
| 0–10 s | README com o diagrama | "Webhook de Pix chega duplicado, atrasado ou nunca chega. Este laboratório prova que cada centavo é creditado uma vez só." |
| 10–20 s | `chaos-profiles/mixed.yml` | "O simulador injeta todas essas falhas, com uma seed: a mesma seed reproduz exatamente a mesma bagunça." |
| 20–55 s | Terminal: `./scripts/demo.sh` | Narrar cada passo: caos injetado, duplicatas descartadas, forjadas rejeitadas, relógio virtual, consulta ativa. |
| 55–70 s | Grafana, dashboard PixLab | "Duplicatas descartadas pela constraint, lag da inbox perto de zero, e o `ledger_imbalance` em zero, sempre." |
| 70–85 s | Jaeger, trace do pagamento | "Um trace só, do pagamento no PSP até o lançamento no ledger, atravessando a fila." |
| 85–90 s | Conciliação `fechado: true` | "Extrato e ledger batem em centavos. Código no GitHub." |

Para reproduzir o GIF do README: `scripts/demo.tape` com o [VHS](https://github.com/charmbracelet/vhs).
