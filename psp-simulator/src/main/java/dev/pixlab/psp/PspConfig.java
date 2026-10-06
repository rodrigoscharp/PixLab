package dev.pixlab.psp;

import dev.pixlab.psp.support.SeedableRandom;
import dev.pixlab.psp.support.VirtualClock;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class PspConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    VirtualClock dispatchClock(Clock clock) {
        return new VirtualClock(clock);
    }

    // Reiniciada com a seed do perfil de caos; sem perfil, usa uma seed qualquer.
    @Bean
    SeedableRandom randomGenerator() {
        return new SeedableRandom(System.nanoTime());
    }
}
