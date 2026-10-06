package dev.pixlab.merchant;

import org.springframework.boot.SpringApplication;

/** Sobe o serviço localmente com Postgres via Testcontainers: ./gradlew :merchant-core:bootTestRun */
public class TestMerchantCoreApplication {

    public static void main(String[] args) {
        SpringApplication.from(MerchantCoreApplication::main).with(TestcontainersConfiguration.class).run(args);
    }
}
