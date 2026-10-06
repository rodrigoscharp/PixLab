package dev.pixlab.merchant.invariants;

import com.sun.net.httpserver.HttpServer;
import dev.pixlab.merchant.MerchantCoreApplication;
import dev.pixlab.merchant.TestcontainersConfiguration;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Contexto do merchant-core compartilhado pelos testes de propriedade (jqwik não usa o SpringExtension).
 * Workers da inbox desligados: o teste decide quando processar, o que permite intercalar entregas e processamento.
 * Um PSP de mentira aceita qualquer pedido com 201.
 */
final class MerchantLab {

    private static ConfigurableApplicationContext context;

    private MerchantLab() {}

    static synchronized ConfigurableApplicationContext context() {
        if (context == null) {
            var psp = stubPsp();
            context = SpringApplication.from(MerchantCoreApplication::main)
                    .with(TestcontainersConfiguration.class)
                    .run("--server.port=0", "--pixlab.inbox.workers=0", "--pixlab.outbox.relay.enabled=false",
                            "--pixlab.consulta-ativa.enabled=false",
                            "--pixlab.psp.base-url=http://localhost:" + psp.getAddress().getPort(),
                            "--pixlab.inbox.backoff-base=0s", "--pixlab.inbox.max-attempts=1000",
                            "--logging.level.dev.pixlab=WARN")
                    .getApplicationContext();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                context.close();
                psp.stop(0);
            }));
        }
        return context;
    }

    static <T> T bean(Class<T> type) {
        return context().getBean(type);
    }

    private static HttpServer stubPsp() {
        try {
            var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                exchange.getRequestBody().readAllBytes();
                var body = "{}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(exchange.getRequestMethod().equals("PUT") ? 201 : 204,
                        exchange.getRequestMethod().equals("PUT") ? body.length : -1);
                if (exchange.getRequestMethod().equals("PUT")) {
                    exchange.getResponseBody().write(body);
                }
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
