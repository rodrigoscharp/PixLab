package dev.pixlab.merchant.outbox;

import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Topologia: exchange de eventos de domínio, fila de auditoria que recebe tudo e sua DLQ. */
@Configuration(proxyBeanMethods = false)
class AmqpConfig {

    static final String EXCHANGE = "pixlab.events";
    static final String AUDIT_QUEUE = "pixlab.events.audit";

    @Bean
    Declarables topology() {
        var exchange = new TopicExchange(EXCHANGE);
        var dlx = new TopicExchange(EXCHANGE + ".dlx");
        var audit = QueueBuilder.durable(AUDIT_QUEUE).deadLetterExchange(dlx.getName()).build();
        var dlq = QueueBuilder.durable(AUDIT_QUEUE + ".dlq").build();
        return new Declarables(exchange, dlx, audit, dlq,
                BindingBuilder.bind(audit).to(exchange).with("#"),
                BindingBuilder.bind(dlq).to(dlx).with("#"));
    }
}
