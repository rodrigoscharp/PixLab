package dev.pixlab.psp.cob;

import dev.pixlab.contracts.pix.CobRequest;
import dev.pixlab.contracts.pix.CobResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/cob")
class CobController {

    private final CobService service;

    CobController(CobService service) {
        this.service = service;
    }

    @PutMapping("/{txid}")
    @ResponseStatus(HttpStatus.CREATED)
    CobResponse criar(@PathVariable String txid, @RequestBody CobRequest request) {
        return service.criar(txid, request);
    }

    @GetMapping("/{txid}")
    CobResponse consultar(@PathVariable String txid) {
        return service.consultar(txid);
    }
}
