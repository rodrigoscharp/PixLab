package dev.pixlab.merchant.psp;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param baseUrl      URL base da API Pix do PSP
 * @param chave        chave Pix do recebedor
 * @param webhookUrl   URL que o PSP chama; ele acrescenta {@code /pix}
 * @param cobExpiracao validade das cobranças criadas
 */
@ConfigurationProperties("pixlab.psp")
public record PspProperties(URI baseUrl, String chave, URI webhookUrl, Duration cobExpiracao) {}
