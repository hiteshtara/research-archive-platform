package edu.bu.archive.application.authorization.fixtures;

import java.util.ArrayList;
import java.util.List;

import edu.bu.archive.application.authorization.InstitutionalIdentifier;
import edu.bu.archive.application.authorization.admin.AdminAuditAction;
import edu.bu.archive.application.authorization.admin.AdminAuditSink;

/** Captures administrative audit events so tests can assert on them. */
public class RecordingAdminAuditSink implements AdminAuditSink {

    /** One recorded audit event. */
    public record Event(String actor, AdminAuditAction action, String subject, String detail) {
    }

    private final List<Event> events = new ArrayList<>();

    @Override
    public void record(String actor, AdminAuditAction action,
                       InstitutionalIdentifier subject, String detail) {
        events.add(new Event(actor, action, subject == null ? null : subject.value(), detail));
    }

    public List<Event> events() {
        return List.copyOf(events);
    }

    public List<AdminAuditAction> actions() {
        return events.stream().map(Event::action).toList();
    }
}
