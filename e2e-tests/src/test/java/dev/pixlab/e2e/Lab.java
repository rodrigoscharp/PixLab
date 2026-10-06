package dev.pixlab.e2e;

import java.util.ArrayList;
import java.util.List;
import org.springframework.web.client.RestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/** psp-simulator + merchant-core rodando como processos, com seus bancos e RabbitMQ; um por JVM de teste. */
final class Lab {

    private static Lab instance;

    final ServiceProcess psp;
    final ServiceProcess merchant;
    final RestClient pspApi;
    final RestClient merchantApi;

    private Lab() throws Exception {
        var pspDb = new PostgreSQLContainer("postgres:16-alpine");
        var merchantDb = new PostgreSQLContainer("postgres:16-alpine");
        var rabbit = new RabbitMQContainer("rabbitmq:4-management-alpine");
        pspDb.start();
        merchantDb.start();
        rabbit.start();

        var pspArgs = new ArrayList<>(datasource(pspDb));
        pspArgs.add("--pixlab.chaos.profiles-dir=" + System.getProperty("pixlab.e2e.chaos-profiles"));
        psp = ServiceProcess.start("psp-simulator", "pixlab.e2e.psp-jar", pspArgs);

        var merchantPort = ServiceProcess.freePort();
        var merchantArgs = new ArrayList<>(datasource(merchantDb));
        merchantArgs.add("--spring.rabbitmq.host=" + rabbit.getHost());
        merchantArgs.add("--spring.rabbitmq.port=" + rabbit.getAmqpPort());
        merchantArgs.add("--spring.rabbitmq.username=" + rabbit.getAdminUsername());
        merchantArgs.add("--spring.rabbitmq.password=" + rabbit.getAdminPassword());
        merchantArgs.add("--pixlab.psp.base-url=" + psp.baseUrl());
        merchantArgs.add("--pixlab.psp.webhook-url=http://localhost:" + merchantPort + "/webhook");
        merchant = ServiceProcess.start("merchant-core", "pixlab.e2e.merchant-jar", merchantArgs, merchantPort);

        pspApi = RestClient.create(psp.baseUrl().toString());
        merchantApi = RestClient.create(merchant.baseUrl().toString());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            merchant.close();
            psp.close();
            rabbit.stop();
            merchantDb.stop();
            pspDb.stop();
        }));
    }

    static synchronized Lab get() throws Exception {
        if (instance == null) {
            instance = new Lab();
        }
        return instance;
    }

    private static List<String> datasource(PostgreSQLContainer db) {
        return List.of(
                "--spring.datasource.url=" + db.getJdbcUrl(),
                "--spring.datasource.username=" + db.getUsername(),
                "--spring.datasource.password=" + db.getPassword());
    }
}
