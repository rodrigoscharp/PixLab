package dev.pixlab.merchant.recon;

import dev.pixlab.contracts.pix.Pix;
import dev.pixlab.merchant.psp.PspClient;
import java.time.Instant;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Job de conciliação Pix por janela de tempo. Três passos:
 * <ol>
 *   <li>extrato do PSP → classifica cada Pix e aplica a ação (em chunks, reiniciável);</li>
 *   <li>pagamentos do ledger na janela que o PSP não listou → FALTA_NO_PSP;</li>
 *   <li>resumo e fechamento (invariante 4).</li>
 * </ol>
 */
@Configuration(proxyBeanMethods = false)
class ReconJobConfig {

    static final String JOB = "pixReconJob";

    @Bean
    Job pixReconJob(JobRepository repository, Step reconPspSide, Step reconLedgerSide, Step reconSummary) {
        return new JobBuilder(JOB, repository)
                .start(reconPspSide)
                .next(reconLedgerSide)
                .next(reconSummary)
                .build();
    }

    @Bean
    Step reconPspSide(JobRepository repository, PlatformTransactionManager tx, PspExtratoReader pspExtratoReader,
            Reconciler reconciler, ItemWriter<ReconItem> reconItemWriter, ReconService.Props props) {
        return new StepBuilder("reconPspSide", repository)
                .<Pix, ReconItem>chunk(props.chunkSize())
                .transactionManager(tx)
                .reader(pspExtratoReader)
                .processor(reconciler::classify)
                .writer(reconItemWriter)
                .build();
    }

    @Bean
    Step reconLedgerSide(JobRepository repository, PlatformTransactionManager tx, Reconciler reconciler) {
        return new StepBuilder("reconLedgerSide", repository)
                .tasklet((contribution, context) -> {
                    var params = context.getStepContext().getJobParameters();
                    reconciler.markMissingInPsp((Long) params.get("runId"),
                            Instant.parse((String) params.get("inicio")), Instant.parse((String) params.get("fim")));
                    return RepeatStatus.FINISHED;
                }, tx)
                .build();
    }

    @Bean
    Step reconSummary(JobRepository repository, PlatformTransactionManager tx, ReconRuns runs) {
        return new StepBuilder("reconSummary", repository)
                .tasklet((contribution, context) -> {
                    runs.summarize((Long) context.getStepContext().getJobParameters().get("runId"));
                    return RepeatStatus.FINISHED;
                }, tx)
                .build();
    }

    @Bean
    @StepScope
    PspExtratoReader pspExtratoReader(PspClient psp, ReconService.Props props,
            @Value("#{jobParameters['inicio']}") String inicio, @Value("#{jobParameters['fim']}") String fim) {
        return new PspExtratoReader(psp, Instant.parse(inicio), Instant.parse(fim), props.pageSize());
    }

    @Bean
    @StepScope
    ItemWriter<ReconItem> reconItemWriter(Reconciler reconciler, @Value("#{jobParameters['runId']}") Long runId) {
        return chunk -> chunk.forEach(item -> reconciler.write(runId, item));
    }
}
