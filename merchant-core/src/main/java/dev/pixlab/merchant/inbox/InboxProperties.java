package dev.pixlab.merchant.inbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param workers     threads processando a inbox em paralelo ({@code SKIP LOCKED} evita disputa)
 * @param idle        espera quando não há evento pendente
 * @param maxAttempts tentativas antes de quarentenar um evento que pede retry
 * @param backoffBase atraso da primeira retentativa; dobra a cada tentativa
 * @param backoffMax  teto do atraso entre retentativas
 */
@ConfigurationProperties("pixlab.inbox")
public record InboxProperties(int workers, Duration idle, int maxAttempts, Duration backoffBase, Duration backoffMax) {

    Duration backoff(int attempts) {
        var delay = backoffBase.multipliedBy(1L << Math.min(attempts, 20));
        return delay.compareTo(backoffMax) > 0 ? backoffMax : delay;
    }
}
