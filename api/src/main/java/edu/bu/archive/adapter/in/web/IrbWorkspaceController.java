package edu.bu.archive.adapter.in.web;

import edu.bu.archive.adapter.in.web.dto.workspace.IrbWorkspaceResponse;
import edu.bu.archive.adapter.out.persistence.IrbWorkspaceRepository;

import jakarta.validation.constraints.Positive;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/*
 * IRB is out of release scope and has no record authorization. The release
 * configuration sets app.irb.api.enabled=false so this controller is never
 * registered there; absent or true keeps today's behaviour. See
 * docs/security/IRB_RELEASE_EXCLUSION.md.
 */
@ConditionalOnProperty(
        prefix = "app.irb.api",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
@RestController
@RequestMapping("/api/irb/record")
@Validated
public class IrbWorkspaceController {

    private final IrbWorkspaceRepository repository;

    public IrbWorkspaceController(IrbWorkspaceRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/{recordId}/workspace")
    public IrbWorkspaceResponse workspace(
            @PathVariable @Positive Long recordId
    ) {
        return repository.findByArchiveRecordId(recordId);
    }
}
