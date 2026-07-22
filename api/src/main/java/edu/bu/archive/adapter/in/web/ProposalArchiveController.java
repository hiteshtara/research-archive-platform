package edu.bu.archive.adapter.in.web;

import edu.bu.archive.adapter.in.web.dto.proposal.ProposalAwardResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalFamilySummaryResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalPersonResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalVersionPageResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalWorkspaceResponse;
import edu.bu.archive.adapter.out.persistence.ProposalArchiveRepository;
import edu.bu.archive.application.proposal.ProposalArchiveService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/proposals")
public class ProposalArchiveController {

    private final ProposalArchiveService service;
    private final ProposalArchiveRepository repository;

    public ProposalArchiveController(
            ProposalArchiveService service,
            ProposalArchiveRepository repository
    ) {
        this.service = service;
        this.repository = repository;
    }

    @GetMapping("/families")
    public List<ProposalFamilySummaryResponse> families(
            @RequestParam(required = false)
            String query,

            @RequestParam(defaultValue = "50")
            int limit
    ) {
        int safeLimit = Math.min(
                Math.max(limit, 1),
                200
        );

        return repository.findFamilies(
                query,
                safeLimit
        );
    }

    @GetMapping("/{proposalNumber}")
    public ResponseEntity<ProposalWorkspaceResponse> workspace(
            @PathVariable
            String proposalNumber
    ) {
        return ResponseEntity.ok(
                service.findWorkspace(proposalNumber)
        );
    }

    @GetMapping("/{proposalNumber}/history")
    public ResponseEntity<ProposalVersionPageResponse> history(
            @PathVariable
            String proposalNumber,

            @RequestParam(defaultValue = "0")
            int page,

            @RequestParam(defaultValue = "25")
            int size
    ) {
        return ResponseEntity.ok(
                service.findVersionPage(
                        proposalNumber,
                        page,
                        size
                )
        );
    }

    @GetMapping("/{proposalNumber}/people")
    public ResponseEntity<List<ProposalPersonResponse>> people(
            @PathVariable
            String proposalNumber
    ) {
        return ResponseEntity.ok(
                service.findCurrentPeople(proposalNumber)
        );
    }

    @GetMapping("/{proposalNumber}/awards")
    public ResponseEntity<List<ProposalAwardResponse>> awards(
            @PathVariable
            String proposalNumber
    ) {
        return ResponseEntity.ok(
                service.findAwards(proposalNumber)
        );
    }
}
