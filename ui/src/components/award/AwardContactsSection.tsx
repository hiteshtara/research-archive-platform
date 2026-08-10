import { ExpandMoreOutlined } from "@mui/icons-material";
import {
  Accordion,
  AccordionDetails,
  AccordionSummary,
  Chip,
  Divider,
  Stack,
  Typography,
} from "@mui/material";
import { useQuery } from "@tanstack/react-query";
import type { ReactNode } from "react";

import {
  getAwardCentralAdministrationContactsV1,
  getAwardSponsorContactsV1,
  getAwardUnitContactsV1,
  getAwardUnitDetailsV1,
} from "../../api/client";
import {
  hasAnyCentralAdministrationContacts,
  hasAnySponsorContacts,
  hasAnyUnitContacts,
} from "../../features/award/awardSectionsPresentation.mjs";
import type {
  AwardCentralAdministrationContactV1,
  AwardSponsorContactV1,
  AwardUnitContactV1,
} from "../../types/api";
import { EmptyState } from "../common/EmptyState";
import { ErrorState } from "../common/ErrorState";
import { LoadingState } from "../common/LoadingState";
import { AwardPeopleSection } from "./AwardPeopleSection";

function ContactRow({
  fullName,
  projectRole,
  email,
  phone,
  tags,
}: {
  fullName: string | null;
  projectRole: string | null;
  email: string | null;
  phone: string | null;
  tags?: ReactNode;
}) {
  return (
    <Stack spacing={0.25} sx={{ py: 0.75 }}>
      <Stack direction="row" spacing={1} sx={{ alignItems: "center", flexWrap: "wrap" }}>
        <Typography sx={{ fontWeight: 600 }}>
          {fullName ?? "Unnamed contact"}
        </Typography>
        {tags}
      </Stack>
      <Typography variant="body2" color="text.secondary">
        {projectRole ?? "Role not recorded"}
      </Typography>
      <Typography variant="caption" color="text.secondary">
        {email ?? "No email on file"}
        {phone ? ` · ${phone}` : ""}
      </Typography>
    </Stack>
  );
}

function SectionShell({
  title,
  defaultExpanded,
  isLoading,
  isError,
  errorMessage,
  isEmpty,
  emptyMessage,
  children,
}: {
  title: string;
  defaultExpanded?: boolean;
  isLoading: boolean;
  isError: boolean;
  errorMessage: string;
  isEmpty: boolean;
  emptyMessage: string;
  children: ReactNode;
}) {
  return (
    <Accordion variant="outlined" disableGutters defaultExpanded={defaultExpanded}>
      <AccordionSummary expandIcon={<ExpandMoreOutlined />}>
        <Typography sx={{ fontWeight: 700 }}>{title}</Typography>
      </AccordionSummary>
      <AccordionDetails>
        {isLoading && <LoadingState mode="skeleton" height={64} />}
        {!isLoading && isError && <ErrorState message={errorMessage} />}
        {!isLoading && !isError && isEmpty && (
          <EmptyState variant="text" message={emptyMessage} />
        )}
        {!isLoading && !isError && !isEmpty && children}
      </AccordionDetails>
    </Accordion>
  );
}

function UnitDetailsSubsection({ awardId }: { awardId: number }) {
  const query = useQuery({
    queryKey: ["award-unit-details-v1", awardId],
    queryFn: ({ signal }) => getAwardUnitDetailsV1(awardId, signal),
  });

  const unit = query.data;

  return (
    <SectionShell
      title="Unit Details"
      defaultExpanded
      isLoading={query.isLoading}
      isError={query.isError}
      errorMessage="Unable to load Unit Details."
      isEmpty={!unit}
      emptyMessage="No lead unit is recorded for this Award version."
    >
      {unit && (
        <Stack spacing={0.5}>
          <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
            <Typography sx={{ fontWeight: 600 }}>
              {unit.unitName ?? "Unnamed unit"}
            </Typography>
            {unit.leadUnit && <Chip size="small" color="primary" label="Lead unit" />}
          </Stack>
          <Typography variant="body2" color="text.secondary">
            Unit number: {unit.unitNumber ?? "—"}
          </Typography>
          {unit.parentUnitNumber && (
            <Typography variant="body2" color="text.secondary">
              Parent unit: {unit.parentUnitName ?? unit.parentUnitNumber} (
              {unit.parentUnitNumber})
            </Typography>
          )}
          {unit.organization && (
            <Typography variant="body2" color="text.secondary">
              Organization: {unit.organization}
            </Typography>
          )}
        </Stack>
      )}
    </SectionShell>
  );
}

function UnitContactsSubsection({ awardId }: { awardId: number }) {
  const query = useQuery({
    queryKey: ["award-unit-contacts-v1", awardId],
    queryFn: ({ signal }) => getAwardUnitContactsV1(awardId, signal),
  });

  const contacts: AwardUnitContactV1[] = query.data ?? [];

  return (
    <SectionShell
      title="Unit Contacts"
      isLoading={query.isLoading}
      isError={query.isError}
      errorMessage="Unable to load Unit Contacts."
      isEmpty={!hasAnyUnitContacts(contacts)}
      emptyMessage="No Unit Contacts are recorded for this Award version."
    >
      <Stack divider={<Divider />}>
        {contacts.map((contact, index) => (
          <ContactRow
            key={index}
            fullName={contact.fullName}
            projectRole={contact.projectRole}
            email={contact.email}
            phone={contact.phone}
            tags={
              <>
                {contact.unitNumber && (
                  <Chip size="small" variant="outlined" label={contact.unitNumber} />
                )}
                {contact.leadUnit && <Chip size="small" label="Lead unit" />}
              </>
            }
          />
        ))}
      </Stack>
    </SectionShell>
  );
}

function SponsorContactsSubsection({ awardId }: { awardId: number }) {
  const query = useQuery({
    queryKey: ["award-sponsor-contacts-v1", awardId],
    queryFn: ({ signal }) => getAwardSponsorContactsV1(awardId, signal),
  });

  const contacts: AwardSponsorContactV1[] = query.data ?? [];

  return (
    <SectionShell
      title="Sponsor Contacts"
      isLoading={query.isLoading}
      isError={query.isError}
      errorMessage="Unable to load Sponsor Contacts."
      isEmpty={!hasAnySponsorContacts(contacts)}
      emptyMessage="No Sponsor Contacts are recorded for this Award version."
    >
      <Stack divider={<Divider />}>
        {contacts.map((contact, index) => (
          <ContactRow
            key={index}
            fullName={contact.fullName}
            projectRole={contact.contactRoleCode}
            email={contact.email}
            phone={contact.phone}
            tags={
              contact.organization ? (
                <Chip size="small" variant="outlined" label={contact.organization} />
              ) : undefined
            }
          />
        ))}
      </Stack>
    </SectionShell>
  );
}

function CentralAdministrationContactsSubsection({
  awardId,
}: {
  awardId: number;
}) {
  const query = useQuery({
    queryKey: ["award-central-administration-contacts-v1", awardId],
    queryFn: ({ signal }) =>
      getAwardCentralAdministrationContactsV1(awardId, signal),
  });

  const contacts: AwardCentralAdministrationContactV1[] = query.data ?? [];

  return (
    <SectionShell
      title="Central Administration Contacts"
      isLoading={query.isLoading}
      isError={query.isError}
      errorMessage="Unable to load Central Administration Contacts."
      isEmpty={!hasAnyCentralAdministrationContacts(contacts)}
      emptyMessage="No Central Administration Contacts are derived for this Award's lead unit."
    >
      <Stack divider={<Divider />}>
        {contacts.map((contact, index) => (
          <ContactRow
            key={index}
            fullName={contact.fullName}
            projectRole={contact.projectRole}
            email={contact.email}
            phone={contact.phone}
          />
        ))}
      </Stack>
    </SectionShell>
  );
}

// People and Units, reorganized to mirror Kuali's own layout: Key
// Personnel (unchanged, still GET /api/v1/awards/{awardId}/people) plus
// four collapsible sections built on the shared Unit/UnitAdministrator/
// UnitAdministratorType/Rolodex/Person reference model - never a
// second, Award-owned copy of Unit data. Central Administration
// Contacts is DERIVED (reproduces Kuali's own
// Award.initCentralAdminContacts() exactly); Unit Contacts and Sponsor
// Contacts are real, already-archived Award-specific data. See
// docs/architecture/AWARD_CONTACTS_DESIGN.md.
export function AwardContactsSection({ awardId }: { awardId: number }) {
  return (
    <Stack spacing={1.5}>
      <Accordion variant="outlined" disableGutters defaultExpanded>
        <AccordionSummary expandIcon={<ExpandMoreOutlined />}>
          <Typography sx={{ fontWeight: 700 }}>Key Personnel</Typography>
        </AccordionSummary>
        <AccordionDetails>
          <AwardPeopleSection awardId={awardId} />
        </AccordionDetails>
      </Accordion>

      <UnitDetailsSubsection awardId={awardId} />
      <UnitContactsSubsection awardId={awardId} />
      <SponsorContactsSubsection awardId={awardId} />
      <CentralAdministrationContactsSubsection awardId={awardId} />
    </Stack>
  );
}
