package dev.pixlab.merchant.payment;

import dev.pixlab.contracts.pix.WebhookSignature;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejeita webhooks sem assinatura HMAC válida antes de qualquer efeito (PIX-BAD-AUTH). O corpo é lido uma vez,
 * verificado e repassado ao controller.
 */
@Component
class WebhookSignatureFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(WebhookSignatureFilter.class);

    private final String secret;
    private final Counter rejected;

    WebhookSignatureFilter(@Value("${pixlab.webhook.secret}") String secret, MeterRegistry meters) {
        this.secret = secret;
        this.rejected = Counter.builder("webhook.rejected").tag("reason", "assinatura")
                .description("Webhooks rejeitados antes da inbox").register(meters);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/webhook/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var body = request.getInputStream().readAllBytes();
        if (!WebhookSignature.verify(secret, body, request.getHeader(WebhookSignature.HEADER))) {
            log.warn("Webhook com assinatura inválida rejeitado ({} bytes)", body.length);
            rejected.increment();
            response.sendError(HttpStatus.UNAUTHORIZED.value(), "assinatura inválida");
            return;
        }
        chain.doFilter(new CachedBodyRequest(request, body), response);
    }

    /** Request cujo corpo já foi lido; devolve os bytes guardados. */
    private static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            var in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return in.read(b, off, len);
                }

                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(),
                    StandardCharsets.UTF_8));
        }
    }
}
