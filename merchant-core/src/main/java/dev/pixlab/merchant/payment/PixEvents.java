package dev.pixlab.merchant.payment;

import dev.pixlab.contracts.pix.Pix;
import dev.pixlab.merchant.inbox.Inbox;
import dev.pixlab.merchant.refund.RefundEventHandler;
import dev.pixlab.merchant.refund.RefundEventHandler.RefundEvent;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/** Transforma um Pix do PSP em eventos da inbox: o crédito e cada estado de cada devolução. */
final class PixEvents {

    private PixEvents() {}

    /**
     * @param withCredit false para notificações de devolução: o crédito chega por seu próprio webhook ou pela
     *                   consulta ativa, e é isso que deixa PIX-OOO acontecer de verdade
     * @return quantos eventos eram novos
     */
    static int offer(Inbox inbox, JsonMapper json, Pix pix, boolean withCredit) {
        int injected = 0;
        if (withCredit) {
            var credit = new Pix(pix.endToEndId(), pix.txid(), pix.valor(), pix.horario(), pix.infoPagador(), List.of());
            if (inbox.offer(PixCreditHandler.SOURCE, pix.endToEndId(), json.writeValueAsString(credit))) {
                injected++;
            }
        }
        if (pix.devolucoes() != null) {
            for (var d : pix.devolucoes()) {
                var event = new RefundEvent(pix.endToEndId(), d);
                if (inbox.offer(RefundEventHandler.SOURCE, event.key(), json.writeValueAsString(event))) {
                    injected++;
                }
            }
        }
        return injected;
    }
}
