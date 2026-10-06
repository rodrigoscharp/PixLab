package dev.pixlab.merchant.invariants;

import dev.pixlab.merchant.MerchantCoreApplication;
import dev.pixlab.merchant.TestcontainersConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Contexto do merchant-core compartilhado pelos testes de propriedade (jqwik não usa o SpringExtension).
 * Workers da inbox desligados: o teste decide quando processar, o que permite intercalar entregas e processamento.
 */
final class MerchantLab {

    private static ConfigurableApplicationContext context;

    private MerchantLab() {}

    static synchronized ConfigurableApplicationContext context() {
        if (context == null) {
            context = SpringApplication.from(MerchantCoreApplication::main)
                    .with(TestcontainersConfiguration.class)
                    .run("--server.port=0", "--pixlab.inbox.workers=0", "--pixlab.outbox.relay.enabled=false",
                            "--pixlab.consulta-ativa.enabled=false", "--pixlab.psp.base-url=http://localhost:1",
                            "--logging.level.dev.pixlab=WARN")
                    .getApplicationContext();
            Runtime.getRuntime().addShutdownHook(new Thread(context::close));
        }
        return context;
    }

    static <T> T bean(Class<T> type) {
        return context().getBean(type);
    }
}
