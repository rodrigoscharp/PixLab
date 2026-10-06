package dev.pixlab.merchant;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class MerchantConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
