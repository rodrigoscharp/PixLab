package dev.pixlab.merchant.charge;

import dev.pixlab.contracts.pix.Calendario;
import dev.pixlab.contracts.pix.CobRequest;
import dev.pixlab.contracts.pix.Valor;
import dev.pixlab.merchant.psp.PspClient;
import dev.pixlab.merchant.psp.PspProperties;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ChargeService {

    private final ChargeRepository charges;
    private final PspClient psp;
    private final PspProperties props;
    private final Clock clock;

    ChargeService(ChargeRepository charges, PspClient psp, PspProperties props, Clock clock) {
        this.charges = charges;
        this.psp = psp;
        this.props = props;
        this.clock = clock;
    }

    /**
     * Grava a cobrança e a registra no PSP na mesma transação: se o PSP recusar, nada fica gravado aqui.
     * Se o commit falhar depois do PSP aceitar, sobra uma cob órfã no PSP, que expira sozinha.
     */
    @Transactional
    public Charge create(BigDecimal amount, String description) {
        if (amount.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "valor deve ser positivo");
        }
        var txid = UUID.randomUUID().toString().replace("-", "");
        var now = clock.instant();
        var expiracao = props.cobExpiracao();
        var charge = charges.save(new Charge(txid, amount, description, now, now.plus(expiracao)));
        psp.criarCob(txid, new CobRequest(Calendario.expiraEm((int) expiracao.toSeconds()), Valor.of(amount),
                props.chave(), description));
        return charge;
    }

    @Transactional(readOnly = true)
    public Charge get(String txid) {
        return charges.findByTxid(txid)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "cobrança não encontrada: " + txid));
    }
}
