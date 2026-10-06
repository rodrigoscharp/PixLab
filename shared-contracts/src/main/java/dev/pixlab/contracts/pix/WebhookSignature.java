package dev.pixlab.contracts.pix;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Assinatura HMAC-SHA256 do corpo do webhook, no header {@value #HEADER} como {@code sha256=<hex>}.
 * Substitui o mTLS da API Pix real no laboratório (design doc, seção 12).
 */
public final class WebhookSignature {

    public static final String HEADER = "X-Pixlab-Signature";

    private WebhookSignature() {}

    public static String sign(String secret, byte[] body) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Comparação em tempo constante. */
    public static boolean verify(String secret, byte[] body, String header) {
        if (header == null) {
            return false;
        }
        return MessageDigest.isEqual(sign(secret, body).getBytes(StandardCharsets.UTF_8),
                header.getBytes(StandardCharsets.UTF_8));
    }
}
