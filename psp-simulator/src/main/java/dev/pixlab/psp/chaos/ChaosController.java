package dev.pixlab.psp.chaos;

import dev.pixlab.psp.support.VirtualClock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.dataformat.yaml.YAMLMapper;

/** Controle do laboratório: perfil de caos, relógio do dispatcher e inspeção das entregas. */
@RestController
@RequestMapping("/sim")
class ChaosController {

    record AdvanceRequest(Duration duration) {}

    record ClockResponse(String now, String offset) {}

    record DeliveryView(String e2eId, String status, int attempts, boolean forged, Integer lastStatus,
            String nextAttemptAt) {}

    private final ChaosEngine chaos;
    private final VirtualClock clock;
    private final JdbcClient jdbc;
    private final Path profilesDir;

    ChaosController(ChaosEngine chaos, VirtualClock dispatchClock, JdbcClient jdbc,
            @Value("${pixlab.chaos.profiles-dir}") Path profilesDir) {
        this.chaos = chaos;
        this.clock = dispatchClock;
        this.jdbc = jdbc;
        this.profilesDir = profilesDir;
    }

    @PutMapping("/chaos")
    ChaosProfile apply(@RequestBody ChaosProfile profile) {
        try {
            chaos.apply(profile);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return chaos.current();
    }

    /** Carrega {@code chaos-profiles/<name>.yml}; {@code seed} sobrescreve a do arquivo. */
    @PutMapping("/chaos/profiles/{name}")
    ChaosProfile applyNamed(@PathVariable String name, @RequestParam(required = false) Long seed) {
        if (!name.matches("[a-z0-9-]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "nome de perfil inválido");
        }
        var file = profilesDir.resolve(name + ".yml");
        if (!Files.isRegularFile(file)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "perfil não encontrado: " + file);
        }
        ChaosProfile profile;
        try {
            profile = YAMLMapper.builder().build().readValue(Files.readString(file), ChaosProfile.class);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
        var withName = new ChaosProfile(name, seed != null ? seed : profile.seed(), profile.scenarios());
        return apply(withName);
    }

    @GetMapping("/chaos")
    ChaosProfile current() {
        return chaos.current();
    }

    @DeleteMapping("/chaos")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void reset() {
        chaos.reset();
    }

    @PostMapping("/clock/advance")
    ClockResponse advance(@RequestBody AdvanceRequest request) {
        clock.advance(request.duration());
        return clock();
    }

    @GetMapping("/clock")
    ClockResponse clock() {
        return new ClockResponse(clock.instant().toString(), clock.offset().toString());
    }

    @GetMapping("/webhook-deliveries")
    Map<String, Object> deliveries(@RequestParam(required = false) String e2eId) {
        var rows = jdbc.sql("""
                        select e2e_id, status, attempts, forged, last_status, next_attempt_at from webhook_delivery
                        where (cast(:e2e as varchar) is null or e2e_id = :e2e) order by id""")
                .param("e2e", e2eId)
                .query((rs, n) -> new DeliveryView(rs.getString("e2e_id"), rs.getString("status"), rs.getInt("attempts"),
                        rs.getBoolean("forged"), (Integer) rs.getObject("last_status"),
                        rs.getObject("next_attempt_at", OffsetDateTime.class).toInstant().toString()))
                .list();
        var pending = rows.stream().filter(r -> !r.status().equals("DELIVERED") && !r.status().equals("FAILED")).count();
        return Map.of("pending", pending, "deliveries", rows);
    }
}
