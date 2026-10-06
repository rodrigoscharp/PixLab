package dev.pixlab.psp.chaos;

import dev.pixlab.contracts.pix.Pix;
import dev.pixlab.contracts.pix.Valor;
import dev.pixlab.psp.support.SeedableRandom;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Decide, para cada Pix, como o webhook será entregue. Cada decisão usa um gerador derivado de
 * seed + e2eId + cenário, então a mesma seed reproduz exatamente as mesmas falhas (ADR-0004).
 */
@Component
public class ChaosEngine {

    private static final Logger log = LoggerFactory.getLogger(ChaosEngine.class);

    private final AtomicReference<ChaosProfile> profile = new AtomicReference<>(ChaosProfile.NONE);
    private final SeedableRandom random;
    private final MeterRegistry meters;

    ChaosEngine(SeedableRandom random, MeterRegistry meters) {
        this.random = random;
        this.meters = meters;
    }

    public void apply(ChaosProfile newProfile) {
        newProfile.validate();
        random.reseed(newProfile.seed());
        profile.set(newProfile);
        log.info("Perfil de caos '{}' ativo com seed {}: {}", newProfile.name(), newProfile.seed(),
                newProfile.scenarios() == null ? "{}" : newProfile.scenarios().keySet());
    }

    public void reset() {
        profile.set(ChaosProfile.NONE);
    }

    public ChaosProfile current() {
        return profile.get();
    }

    public boolean happens(ChaosScenario scenario, String key) {
        var cfg = profile.get().config(scenario);
        var injected = cfg != null && rng(scenario, key).nextDouble() < cfg.probability();
        if (injected) {
            meters.counter("psp.chaos.injections", "scenario", scenario.code()).increment();
        }
        return injected;
    }

    public ChaosProfile.ScenarioConfig config(ChaosScenario scenario) {
        return profile.get().config(scenario);
    }

    /** Planeja as entregas do webhook de um Pix. Lista vazia = webhook perdido. */
    public List<DeliveryPlan> plan(Pix pix) {
        var key = pix.endToEndId();
        if (happens(ChaosScenario.PIX_LOST, key)) {
            log.info("[PIX-LOST] {}", key);
            return List.of();
        }
        var payload = pix;
        if (happens(ChaosScenario.PIX_AMOUNT, key)) {
            var cents = 1 + rng(ChaosScenario.PIX_AMOUNT, key + ":delta").nextInt(1000);
            var wrong = Valor.parse(pix.valor()).add(BigDecimal.valueOf(cents, 2));
            payload = new Pix(pix.endToEndId(), pix.txid(), Valor.format(wrong), pix.horario(), pix.infoPagador(),
                    pix.devolucoes());
            log.info("[PIX-AMOUNT] {} valor {} -> {}", key, pix.valor(), payload.valor());
        }
        var delay = Duration.ZERO;
        if (happens(ChaosScenario.PIX_DELAY, key)) {
            var cfg = config(ChaosScenario.PIX_DELAY);
            delay = between(rng(ChaosScenario.PIX_DELAY, key + ":delay"), cfg.minDelay(), cfg.maxDelay());
            log.info("[PIX-DELAY] {} atraso {}", key, delay);
        }
        if (happens(ChaosScenario.PIX_OOO, key)) {
            var cfg = config(ChaosScenario.PIX_OOO);
            delay = delay.plus(cfg.maxDelay() == null ? Duration.ofMinutes(30) : cfg.maxDelay());
            log.info("[PIX-OOO] {} crédito atrasado {}; devoluções chegam antes", key, delay);
        }
        var fakeFailures = 0;
        if (happens(ChaosScenario.PIX_RETRY, key)) {
            fakeFailures = orDefault(config(ChaosScenario.PIX_RETRY).failedAttempts(), 2);
            log.info("[PIX-RETRY] {} {} respostas ignoradas", key, fakeFailures);
        }

        var plans = new ArrayList<DeliveryPlan>();
        if (happens(ChaosScenario.PIX_BAD_AUTH, key)) {
            plans.add(new DeliveryPlan(payload, delay, true, 0));
            log.info("[PIX-BAD-AUTH] {} entrega forjada", key);
        }
        plans.add(new DeliveryPlan(payload, delay, false, fakeFailures));
        if (happens(ChaosScenario.PIX_DUP, key)) {
            var copies = orDefault(config(ChaosScenario.PIX_DUP).copies(), 2);
            for (int i = 1; i <= copies; i++) {
                plans.add(new DeliveryPlan(payload, delay.plusMillis(100L * i), false, 0));
            }
            log.info("[PIX-DUP] {} +{} cópias", key, copies);
        }
        if (happens(ChaosScenario.PIX_DUP_CONC, key)) {
            var copies = orDefault(config(ChaosScenario.PIX_DUP_CONC).copies(), 10);
            for (int i = 0; i < copies; i++) {
                plans.add(new DeliveryPlan(payload, delay, false, 0));
            }
            log.info("[PIX-DUP-CONC] {} +{} cópias simultâneas", key, copies);
        }
        return plans;
    }

    public Duration delay(ChaosScenario scenario, String key) {
        var cfg = config(scenario);
        return between(rng(scenario, key + ":delay"), cfg.minDelay(), cfg.maxDelay());
    }

    private SplittableRandom rng(ChaosScenario scenario, String key) {
        long h = profile.get().seed() * 0x9E3779B97F4A7C15L;
        h ^= key.hashCode() * 0xC2B2AE3D27D4EB4FL;
        h ^= (scenario.ordinal() + 1L) * 0x165667B19E3779F9L;
        return new SplittableRandom(h);
    }

    private static Duration between(SplittableRandom rng, Duration min, Duration max) {
        var lo = min == null ? Duration.ZERO : min;
        var hi = max == null ? lo : max;
        if (hi.compareTo(lo) <= 0) {
            return lo;
        }
        return lo.plusMillis(rng.nextLong(hi.minus(lo).toMillis() + 1));
    }

    private static int orDefault(Integer value, int fallback) {
        return value == null ? fallback : value;
    }
}
