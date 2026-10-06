package dev.pixlab.psp.chaos;

import dev.pixlab.contracts.pix.Pix;
import java.time.Duration;

/**
 * Uma entrega de webhook planejada pelo motor de caos.
 *
 * @param payload       o que vai no corpo (pode divergir do extrato em PIX-AMOUNT)
 * @param delay         quanto esperar, no relógio do dispatcher, antes da primeira tentativa
 * @param forged        assinatura inválida (PIX-BAD-AUTH)
 * @param fakeFailures  quantas respostas 2xx tratar como timeout (PIX-RETRY)
 */
public record DeliveryPlan(Pix payload, Duration delay, boolean forged, int fakeFailures) {

    public static DeliveryPlan now(Pix payload) {
        return new DeliveryPlan(payload, Duration.ZERO, false, 0);
    }
}
