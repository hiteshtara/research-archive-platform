package edu.bu.archive.adapter.in.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import edu.bu.archive.application.authorization.RecordAuthorizationService;

/**
 * What kind of archive access the caller has - mode (enforced or not) and
 * grant kinds only. Never returns identifiers, units, IO values or records.
 */
@RestController
@RequestMapping("/api/v1/me")
public class AccessStatusController {

    private final org.springframework.beans.factory.ObjectProvider<RecordAuthorizationService> authorization;

    public AccessStatusController(
            org.springframework.beans.factory.ObjectProvider<RecordAuthorizationService> authorization
    ) {
        this.authorization = authorization;
    }

    @GetMapping("/access")
    public RecordAuthorizationService.AccessStatus access() {
        RecordAuthorizationService service = authorization.getIfAvailable();
        return service == null
                ? new RecordAuthorizationService.AccessStatus("NOT_ENFORCED",
                        edu.bu.archive.application.authorization.RecordAuthorizationGate.NOT_ENFORCED_LABEL,
                        null, java.util.List.of())
                : service.status();
    }
}
