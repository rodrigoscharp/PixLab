package dev.pixlab.psp;

import org.springframework.boot.SpringApplication;

/** Sobe o serviço localmente com Postgres via Testcontainers: ./gradlew :psp-simulator:bootTestRun */
public class TestPspSimulatorApplication {

    public static void main(String[] args) {
        SpringApplication.from(PspSimulatorApplication::main).with(TestcontainersConfiguration.class).run(args);
    }
}
