import { ExpandMoreOutlined } from "@mui/icons-material";
import {
  Accordion,
  AccordionDetails,
  AccordionSummary,
  Alert,
  Box,
  Card,
  CardContent,
  Chip,
  Pagination,
  Skeleton,
  Stack,
  Typography,
} from "@mui/material";
import { useQuery } from "@tanstack/react-query";
import { useState } from "react";

import { getAwardSapTransmissionsV1 } from "../../api/client";
import {
  hasAnyTransmissions,
  xmlDisplayText,
} from "../../features/award/awardSectionsPresentation.mjs";

const PAGE_SIZE = 25;

// A read-only, monospace text viewer for raw XML - always rendered as
// plain text content, via a JSX child expression rather than a raw
// HTML injection API, so a stored SOAP payload can never execute as
// markup in the browser.
function XmlViewer({ label, xml }: { label: string; xml: string | null }) {
  const text = xmlDisplayText(xml);

  if (!text) {
    return (
      <Typography variant="body2" color="text.secondary">
        {label}: not recorded.
      </Typography>
    );
  }

  return (
    <Box>
      <Typography
        variant="caption"
        color="text.secondary"
        sx={{ textTransform: "uppercase", letterSpacing: "0.04em" }}
      >
        {label}
      </Typography>
      <Box
        component="pre"
        sx={{
          mt: 0.5,
          p: 1.5,
          borderRadius: 1,
          backgroundColor: "action.hover",
          fontFamily: "monospace",
          fontSize: 12,
          overflowX: "auto",
          whiteSpace: "pre-wrap",
          wordBreak: "break-word",
          maxHeight: 320,
          overflowY: "auto",
        }}
      >
        {text}
      </Box>
    </Box>
  );
}

// SAP Transmission History - lazy-loads from GET
// /api/v1/awards/{awardId}/sap-transmissions. Sent/returned XML stays
// collapsed by default and is only ever rendered as text.
export function AwardSapTransmissionsSection({ awardId }: { awardId: number }) {
  const [page, setPage] = useState(0);

  const transmissionsQuery = useQuery({
    queryKey: ["award-sap-transmissions-v1", awardId, page],
    queryFn: ({ signal }) =>
      getAwardSapTransmissionsV1(awardId, { page, size: PAGE_SIZE }, signal),
  });

  if (transmissionsQuery.isLoading) {
    return (
      <Stack spacing={1.5}>
        <Skeleton variant="rounded" height={96} />
        <Skeleton variant="rounded" height={96} />
      </Stack>
    );
  }

  if (transmissionsQuery.isError) {
    return (
      <Alert severity="error">Unable to load SAP transmission history.</Alert>
    );
  }

  const transmissions = transmissionsQuery.data;

  if (!transmissions) {
    return null;
  }

  if (!hasAnyTransmissions(transmissions.content)) {
    return (
      <Typography color="text.secondary">
        No SAP transmissions are recorded for this Award.
      </Typography>
    );
  }

  return (
    <Stack spacing={2}>
      {transmissions.content.map((transmission) => (
        <Card key={transmission.transmissionId} variant="outlined">
          <CardContent>
            <Stack
              direction="row"
              spacing={1}
              sx={{ alignItems: "center", flexWrap: "wrap" }}
            >
              <Typography sx={{ fontWeight: 700 }}>
                {transmission.transmissionDate ?? "Date unrecorded"}
              </Typography>

              <Chip
                size="small"
                color={transmission.successful ? "success" : "error"}
                label={transmission.successful ? "Succeeded" : "Failed"}
              />

              {transmission.documentNumber && (
                <Chip
                  size="small"
                  variant="outlined"
                  label={`Doc ${transmission.documentNumber}`}
                />
              )}
            </Stack>

            <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
              Initiator: {transmission.initiatorId ?? "—"} · Transmitter:{" "}
              {transmission.transmitterId ?? "—"}
            </Typography>

            <Typography variant="body2" color="text.secondary">
              Basis of payment: {transmission.basisOfPaymentCode ?? "—"} ·
              Method of payment: {transmission.methodOfPaymentCode ?? "—"}
            </Typography>

            {transmission.children.length > 0 && (
              <Box sx={{ mt: 1.5 }}>
                <Typography
                  variant="caption"
                  color="text.secondary"
                  sx={{ textTransform: "uppercase", letterSpacing: "0.04em" }}
                >
                  Child transmissions
                </Typography>
                <Stack spacing={0.5} sx={{ mt: 0.5 }}>
                  {transmission.children.map((child) => (
                    <Typography key={child.transmissionChildId} variant="body2">
                      {child.awardNumber ?? "Unknown award"} ·{" "}
                      {child.childType ?? "Unknown type"} · Doc{" "}
                      {child.childDocumentNumber ?? "—"}
                    </Typography>
                  ))}
                </Stack>
              </Box>
            )}

            <Accordion
              variant="outlined"
              disableGutters
              sx={{ mt: 1.5, "&:before": { display: "none" } }}
            >
              <AccordionSummary expandIcon={<ExpandMoreOutlined />}>
                <Typography variant="body2">Sent and returned XML</Typography>
              </AccordionSummary>
              <AccordionDetails>
                <Stack spacing={1.5}>
                  <XmlViewer label="Sent" xml={transmission.sentData} />
                  <XmlViewer label="Returned" xml={transmission.returnedData} />
                </Stack>
              </AccordionDetails>
            </Accordion>
          </CardContent>
        </Card>
      ))}

      {transmissions.totalPages > 1 && (
        <Box sx={{ display: "flex", justifyContent: "center" }}>
          <Pagination
            count={transmissions.totalPages}
            page={page + 1}
            onChange={(_event, value) => setPage(value - 1)}
          />
        </Box>
      )}
    </Stack>
  );
}
