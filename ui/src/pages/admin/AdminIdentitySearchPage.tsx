import { useState } from "react";
import {
  Alert,
  AlertTitle,
  Box,
  Button,
  Chip,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import { useQuery } from "@tanstack/react-query";
import { Link as RouterLink } from "react-router-dom";

import { searchAdminIdentities } from "../../api/adminClient";
import { searchSourceNote } from "../../features/admin/adminPresentation.mjs";

/**
 * Find a person to administer.
 *
 * The archive cannot search BU's directory, so every hit is labelled with the
 * store it came from: already linked, has signed in, or appears only in the
 * historical Kuali extract. An administrator must be able to tell those apart
 * before linking anyone, which is why the source note is shown on every row
 * rather than hidden behind a tooltip.
 */
export function AdminIdentitySearchPage() {
  const [term, setTerm] = useState("");
  const [submitted, setSubmitted] = useState("");

  const results = useQuery({
    queryKey: ["admin", "identities", submitted],
    queryFn: ({ signal }) => searchAdminIdentities(submitted, signal),
    enabled: submitted.trim().length > 0,
  });

  return (
    <Stack spacing={3} sx={{ p: 3 }}>
      <Box>
        <Typography variant="h5">Find an identity</Typography>
        <Typography variant="body2" color="text.secondary">
          Search people who have signed in, identities already linked, and the
          historical Kuali extract. BU&rsquo;s directory is not searchable from
          the archive.
        </Typography>
      </Box>

      <Stack direction="row" spacing={2} component="form" onSubmit={(event) => {
        event.preventDefault();
        setSubmitted(term);
      }}>
        <TextField
          fullWidth
          size="small"
          label="Name, login, identifier or Kuali person id"
          value={term}
          onChange={(event) => setTerm(event.target.value)}
        />
        <Button type="submit" variant="contained" disabled={!term.trim()}>
          Search
        </Button>
      </Stack>

      {results.isError && (
        <Alert severity="error">
          The search could not be completed. If Security Administration is not
            enabled in this environment, the administrative API is not
            available &mdash; separate from record-level authorization,
            which can stay off.
        </Alert>
      )}

      {results.data?.length === 0 && (
        <Alert severity="info" data-testid="no-match">
          <AlertTitle sx={{ fontWeight: 700, mb: 0.25 }}>No match</AlertTitle>
          This searched three things: people who have signed in to the
          archive, identities already linked to an institutional identifier,
          and the historical Kuali extract.{" "}
          <strong>
            It does not search BU&rsquo;s directory, and it does not search
            Cognito accounts that have never been linked.
          </strong>{" "}
          So someone with a working BU login can be absent from here: a login
          name only becomes searchable once their identity has been linked.
          The historical extract is narrow by design too &mdash; it covers
          people referenced as unit administrators or award contacts, not
          everyone at BU.{" "}
          They can still be granted access by entering their institutional
          identifier directly.
        </Alert>
      )}

      <Stack spacing={2}>
        {(results.data ?? []).map((hit, index) => (
          <Box
            key={`${hit.source}-${hit.institutionalIdentifier ?? hit.kualiPersonId ?? index}`}
            sx={{ border: 1, borderColor: "divider", borderRadius: 1, p: 2 }}
          >
            <Stack direction="row" spacing={1} sx={{ alignItems: "center", flexWrap: "wrap" }}>
              <Typography variant="subtitle1">
                {hit.displayName ?? hit.institutionalIdentifier ?? hit.kualiPersonId}
              </Typography>
              <Chip
                size="small"
                label={hit.source.replace(/_/g, " ").toLowerCase()}
                color={hit.source === "IDENTITY_LINK" ? "primary" : "default"}
              />
              {hit.hasActiveLink && <Chip size="small" label="linked" color="success" />}
            </Stack>
            <Typography variant="body2" color="text.secondary">
              {hit.sourceNote || searchSourceNote(hit.source)}
            </Typography>
            {hit.institutionalIdentifier ? (
              <Button
                component={RouterLink}
                to={`/admin/identities/${encodeURIComponent(hit.institutionalIdentifier)}`}
                size="small"
                sx={{ mt: 1 }}
              >
                Manage access
              </Button>
            ) : (
              <Typography variant="caption" color="text.secondary">
                Historical record only. Kuali person id{" "}
                {hit.kualiPersonId ?? "unknown"} — link it to an institutional
                identity to grant access.
              </Typography>
            )}
          </Box>
        ))}
      </Stack>
    </Stack>
  );
}
