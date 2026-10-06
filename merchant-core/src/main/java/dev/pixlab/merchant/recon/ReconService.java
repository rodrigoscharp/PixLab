package dev.pixlab.merchant.recon;

import dev.pixlab.merchant.support.Poller;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;

/** Dispara e reinicia execuções da conciliação. */
@Service
public class ReconService {

    /**
     * @param interval intervalo da conciliação automática
     * @param window   tamanho da janela conciliada
     * @param lag       margem até agora, para não conciliar o que ainda está chegando
     * @param chunkSize itens por transação do passo do extrato
     * @param pageSize  itens por página lida do PSP
     */
    @ConfigurationProperties("pixlab.recon")
    public record Props(boolean enabled, Duration interval, Duration window, Duration lag, int chunkSize, int pageSize) {}

    private static final Logger log = LoggerFactory.getLogger(ReconService.class);

    private final JobOperator jobs;
    private final JobRepository repository;
    private final Job job;
    private final ReconRuns runs;
    private final Clock clock;
    private final Props props;

    ReconService(JobOperator jobs, JobRepository repository, Job pixReconJob, ReconRuns runs, Clock clock,
            Props props) {
        this.jobs = jobs;
        this.repository = repository;
        this.job = pixReconJob;
        this.runs = runs;
        this.clock = clock;
        this.props = props;
    }

    public ReconRuns.Run run(Instant inicio, Instant fim) throws Exception {
        if (!fim.isAfter(inicio)) {
            throw new IllegalArgumentException("fim deve ser depois de inicio");
        }
        var runId = runs.create(inicio, fim);
        var params = new JobParametersBuilder()
                .addLong("runId", runId)
                .addString("inicio", inicio.toString())
                .addString("fim", fim.toString())
                .toJobParameters();
        var execution = jobs.start(job, params);
        record(runId, execution.getId(), execution.getStatus());
        return runs.find(runId).orElseThrow();
    }

    /** Reinicia uma execução que falhou: continua do último chunk confirmado, sem repetir efeitos. */
    public ReconRuns.Run restart(long runId) throws Exception {
        var run = runs.find(runId).orElseThrow(() -> new IllegalArgumentException("execução não encontrada: " + runId));
        var last = repository.getJobExecution(run.jobExecutionId());
        var execution = jobs.restart(last);
        record(runId, execution.getId(), execution.getStatus());
        return runs.find(runId).orElseThrow();
    }

    private void record(long runId, long executionId, BatchStatus status) {
        runs.update(runId, status.name(), executionId, status != BatchStatus.STARTED && status != BatchStatus.STARTING);
        log.info("Conciliação {} (execução {}): {}", runId, executionId, status);
    }

    boolean tick() {
        var fim = clock.instant().minus(props.lag());
        try {
            run(fim.minus(props.window()), fim);
        } catch (Exception e) {
            log.warn("Conciliação automática falhou: {}", e.toString());
        }
        return false;
    }

    @Configuration(proxyBeanMethods = false)
    static class PollerConfig {

        @Bean
        Poller reconPoller(ReconService recon, Props props) {
            return new Poller("recon", props.enabled() ? 1 : 0, props.interval(), recon::tick);
        }
    }
}
