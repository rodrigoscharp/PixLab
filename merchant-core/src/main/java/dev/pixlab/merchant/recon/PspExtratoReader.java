package dev.pixlab.merchant.recon;

import dev.pixlab.contracts.pix.Pix;
import dev.pixlab.merchant.psp.PspClient;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import org.springframework.batch.infrastructure.item.support.AbstractItemCountingItemStreamItemReader;

/**
 * Lê o extrato do PSP na janela, página a página. Reiniciável: o Spring Batch guarda quantos itens já foram
 * lidos e, num restart, pula até eles.
 */
class PspExtratoReader extends AbstractItemCountingItemStreamItemReader<Pix> {

    private final PspClient psp;
    private final Instant inicio;
    private final Instant fim;
    private final int pageSize;
    private final Deque<Pix> buffer = new ArrayDeque<>();
    private int nextPage;
    private int totalPages = Integer.MAX_VALUE;

    PspExtratoReader(PspClient psp, Instant inicio, Instant fim, int pageSize) {
        this.psp = psp;
        this.inicio = inicio;
        this.fim = fim;
        this.pageSize = pageSize;
        setName("pspExtrato");
    }

    @Override
    protected Pix doRead() {
        if (buffer.isEmpty() && nextPage < totalPages) {
            var page = psp.listarPixPagina(inicio, fim, nextPage, pageSize);
            totalPages = page.parametros().paginacao().quantidadeDePaginas();
            buffer.addAll(page.pix());
            nextPage++;
        }
        return buffer.poll();
    }

    @Override
    protected void doOpen() {
        buffer.clear();
        nextPage = 0;
        totalPages = Integer.MAX_VALUE;
    }

    @Override
    protected void doClose() {
        buffer.clear();
    }
}
