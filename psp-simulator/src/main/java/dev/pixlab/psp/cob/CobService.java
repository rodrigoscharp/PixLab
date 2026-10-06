package dev.pixlab.psp.cob;

import dev.pixlab.contracts.pix.Calendario;
import dev.pixlab.contracts.pix.CobRequest;
import dev.pixlab.contracts.pix.CobResponse;
import dev.pixlab.contracts.pix.Valor;
import dev.pixlab.psp.pix.PixRecebido;
import dev.pixlab.psp.pix.PixRecebidoRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CobService {

    private static final Pattern TXID = Pattern.compile("[a-zA-Z0-9]{26,35}");
    private static final int EXPIRACAO_PADRAO = 86_400;

    private final CobRepository cobs;
    private final PixRecebidoRepository extrato;
    private final Clock clock;

    CobService(CobRepository cobs, PixRecebidoRepository extrato, Clock clock) {
        this.cobs = cobs;
        this.extrato = extrato;
        this.clock = clock;
    }

    @Transactional
    public CobResponse criar(String txid, CobRequest request) {
        if (!TXID.matcher(txid).matches()) {
            throw badRequest("txid deve seguir [a-zA-Z0-9]{26,35}");
        }
        if (request.valor() == null || request.chave() == null || request.chave().isBlank()) {
            throw badRequest("valor.original e chave são obrigatórios");
        }
        if (cobs.existsById(txid)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "txid já utilizado: " + txid);
        }
        int expiracao = request.calendario() != null && request.calendario().expiracao() != null
                ? request.calendario().expiracao()
                : EXPIRACAO_PADRAO;
        if (expiracao <= 0) {
            throw badRequest("calendario.expiracao deve ser positivo");
        }
        Cob cob = new Cob(txid, request.chave(), valor(request.valor()), request.solicitacaoPagador(),
                clock.instant(), expiracao);
        return toResponse(cobs.save(cob));
    }

    @Transactional(readOnly = true)
    public CobResponse consultar(String txid) {
        return toResponse(buscar(txid));
    }

    private Cob buscar(String txid) {
        return cobs.findById(txid)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "cobrança não encontrada: " + txid));
    }

    private CobResponse toResponse(Cob cob) {
        var pix = extrato.findByTxidOrderByHorario(cob.getTxid()).stream().map(PixRecebido::toContract).toList();
        return new CobResponse(
                cob.getTxid(),
                cob.getStatus(),
                new Calendario(cob.getCriacao().toString(), cob.getExpiracao()),
                Valor.of(cob.getValor()),
                cob.getChave(),
                cob.getSolicitacaoPagador(),
                pix);
    }

    private static BigDecimal valor(Valor valor) {
        try {
            var amount = valor.toBigDecimal();
            if (amount.signum() <= 0) {
                throw badRequest("valor.original deve ser positivo");
            }
            return amount;
        } catch (IllegalArgumentException e) {
            throw badRequest(e.getMessage());
        }
    }

    private static ResponseStatusException badRequest(String detalhe) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, detalhe);
    }
}
