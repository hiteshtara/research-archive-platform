package edu.bu.archive.adapter.out.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "v_irb_search", schema = "archive")
public class IrbSearchEntity {

    @Id
    @Column(name = "record_id")
    private Long recordId;

    @Column(name = "study_id")
    private String studyId;

    @Column(name = "protocol_base")
    private String protocolBase;

    @Column(name = "protocol_number")
    private String protocolNumber;

    @Column(name = "title")
    private String title;

    @Column(name = "protocol_type")
    private String protocolType;

    @Column(name = "protocol_status")
    private String protocolStatus;

    @Column(name = "approval_date")
    private LocalDate approvalDate;

    @Column(name = "pi_buid")
    private String piBuid;

    @Column(name = "pi_first_name")
    private String piFirstName;

    @Column(name = "pi_last_name")
    private String piLastName;

    @Column(name = "pi_full_name")
    private String piFullName;

    @Column(name = "pi_email")
    private String piEmail;

    @Column(name = "pi_buid_missing")
    private boolean piBuidMissing;

    @Column(name = "active_flag")
    private boolean activeFlag;

    @Column(name = "loaded_at")
    private Instant loadedAt;

    protected IrbSearchEntity() {
    }

    public Long getRecordId() {
        return recordId;
    }

    public String getStudyId() {
        return studyId;
    }

    public String getProtocolBase() {
        return protocolBase;
    }

    public String getProtocolNumber() {
        return protocolNumber;
    }

    public String getTitle() {
        return title;
    }

    public String getProtocolType() {
        return protocolType;
    }

    public String getProtocolStatus() {
        return protocolStatus;
    }

    public LocalDate getApprovalDate() {
        return approvalDate;
    }

    public String getPiBuid() {
        return piBuid;
    }

    public String getPiFirstName() {
        return piFirstName;
    }

    public String getPiLastName() {
        return piLastName;
    }

    public String getPiFullName() {
        return piFullName;
    }

    public String getPiEmail() {
        return piEmail;
    }

    public boolean isPiBuidMissing() {
        return piBuidMissing;
    }

    public boolean isActiveFlag() {
        return activeFlag;
    }

    public Instant getLoadedAt() {
        return loadedAt;
    }
}
