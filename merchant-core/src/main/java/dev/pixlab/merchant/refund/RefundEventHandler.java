package dev.pixlab.merchant.refund;

import dev.pixlab.contracts.pix.Devolucao;
import dev.pixlab.contracts.pix.Valor;
import dev.pixlab.merchant.inbox.InboxEvent;
import dev.pixlab.merchant.inbox.InboxEventHandler;
import dev.pixlab.merchant.inbox.Outcome;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Eventos de devolução da inbox ({@code source = PIX_DEVOLUCAO}, chave {@code e2eId:id:status}). */
@Component
public class RefundEventHandler implements InboxEventHandler {

    public static final String SOURCE = "PIX_DEVOLUCAO";

    /** Payload do evento: a devolução e o Pix a que pertence. */
    public record RefundEvent(String endToEndId, Devolucao devolucao) {

        public String key() {
            return endToEndId + ":" + devolucao.id() + ":" + devolucao.status();
        }
    }

    private final RefundService refunds;
    private final JsonMapper json;

    RefundEventHandler(RefundService refunds, JsonMapper json) {
        this.refunds = refunds;
        this.json = json;
    }

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    public Outcome handle(InboxEvent event) {
        var e = json.readValue(event.payload(), RefundEvent.class);
        var d = e.devolucao();
        return switch (refunds.apply(e.endToEndId(), d.id(), d.rtrId(), Valor.parse(d.valor()), d.natureza(),
                RefundStatus.valueOf(d.status()))) {
            case APPLIED -> Outcome.APPLIED;
            // PIX-OOO: a devolução chegou antes do crédito. Espera; a consulta ativa traz o crédito se ele se perdeu.
            case PAYMENT_NOT_FOUND -> new Outcome.Retry("crédito do Pix " + e.endToEndId() + " ainda não chegou");
            // Pode ser a nossa própria devolução cujo pedido ainda não confirmou; se nunca aparecer, vai para quarentena.
            case UNKNOWN_REFUND -> new Outcome.Retry("devolução " + d.id() + " desconhecida");
            case OVER_LIMIT -> new Outcome.Quarantine("MED " + d.id() + " excede o saldo devolvível do Pix");
        };
    }
}
