package edu.bu.archive.application.authorization.fixtures;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import edu.bu.archive.application.authorization.admin.AdminAuditRepository;
import edu.bu.archive.application.authorization.admin.AuditEntry;
import edu.bu.archive.application.authorization.admin.AuditQuery;

/** TEST-ONLY in-memory audit reader. */
public final class InMemoryAdminAuditRepository implements AdminAuditRepository {

    private final List<AuditEntry> entries = new ArrayList<>();

    public void add(AuditEntry entry) {
        entries.add(entry);
    }

    @Override
    public List<AuditEntry> find(AuditQuery query) {
        return entries.stream()
                .filter(entry -> query.subject() == null || query.subject().equals(entry.subject()))
                .filter(entry -> query.action() == null || query.action().name().equals(entry.action()))
                .filter(entry -> query.from() == null || !entry.occurredAt().isBefore(query.from()))
                .filter(entry -> query.to() == null || entry.occurredAt().isBefore(query.to()))
                .sorted(Comparator.comparing(AuditEntry::occurredAt).reversed())
                .limit(query.limit())
                .toList();
    }
}
