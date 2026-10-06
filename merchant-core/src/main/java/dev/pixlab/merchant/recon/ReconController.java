package dev.pixlab.merchant.recon;

import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/recon/runs")
class ReconController {

    record RunRequest(Instant inicio, Instant fim) {}

    private final ReconService recon;
    private final ReconRuns runs;

    ReconController(ReconService recon, ReconRuns runs) {
        this.recon = recon;
        this.runs = runs;
    }

    @PostMapping
    ReconRuns.Run run(@RequestBody RunRequest request) throws Exception {
        try {
            return recon.run(request.inicio(), request.fim());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping("/{id}/restart")
    ReconRuns.Run restart(@PathVariable long id) throws Exception {
        return recon.restart(id);
    }

    @GetMapping("/{id}")
    ReconRuns.Run get(@PathVariable long id) {
        return runs.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @GetMapping("/{id}/items")
    List<ReconRuns.Item> items(@PathVariable long id) {
        return runs.items(id);
    }
}
