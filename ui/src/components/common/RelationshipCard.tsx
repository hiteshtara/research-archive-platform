import { ArrowForwardOutlined } from "@mui/icons-material";
import {
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  Stack,
  Typography,
} from "@mui/material";
import type { ReactNode } from "react";
import { Link as RouterLink } from "react-router-dom";

// One card per cross-module relationship row (Award<->Subaward,
// Award<->Proposal, Award<->Negotiation, Proposal<->Award,
// Negotiation<->its associated record). Standardized (Sprint 3) on the
// horizontal title-left/button-right layout that 4 of the 6 original
// consumers already used, and on "always show the card's content, just
// disable the button" for a not-currently-archived target - both
// deliberate choices, not a guess: Sprint 1's original stacked layout
// and Subaward's own "hide everything but a chip when not archived"
// behavior are the two things this sprint intentionally changes for
// Subaward's consumer, to converge on one consistent pattern
// everywhere else already used.
export function RelationshipCard({
  title,
  archived,
  notArchivedLabel = "Not currently archived",
  statusLabel,
  subtitle,
  metaText,
  amountText,
  inactive = false,
  inactiveLabel = "Inactive",
  buttonLabel,
  href,
  onOpen,
}: {
  title: string;
  archived: boolean;
  notArchivedLabel?: string;
  statusLabel?: ReactNode;
  subtitle?: string | null;
  metaText?: string | null;
  amountText?: string | null;
  // Relationship-level (not target-navigability) state - e.g.
  // archive.award_funding_proposal/proposal_award's own active flag.
  // Preserved for audit, never hidden: dims the whole card and adds an
  // "Inactive" chip next to the title, independent of whether the
  // target itself is currently archived/navigable.
  inactive?: boolean;
  inactiveLabel?: string;
  buttonLabel: string;
  href?: string;
  onOpen?: () => void;
}) {
  return (
    <Card variant="outlined" sx={{ opacity: inactive ? 0.6 : 1 }}>
      <CardContent
        sx={{
          display: "flex",
          justifyContent: "space-between",
          alignItems: "center",
          "&:last-child": { pb: 2 },
        }}
      >
        <Box>
          <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
            <Typography sx={{ fontWeight: 700 }}>{title}</Typography>
            {inactive && (
              <Chip size="small" variant="outlined" label={inactiveLabel} />
            )}
          </Stack>

          {subtitle && (
            <Typography variant="body2" color="text.secondary">
              {subtitle}
            </Typography>
          )}

          <Stack
            direction="row"
            spacing={1}
            sx={{ mt: 0.5, alignItems: "center", flexWrap: "wrap" }}
          >
            {archived ? (
              statusLabel
            ) : (
              <Chip
                size="small"
                variant="outlined"
                label={notArchivedLabel}
              />
            )}
            {metaText && (
              <Typography variant="caption" color="text.secondary">
                {metaText}
              </Typography>
            )}
            {amountText && (
              <Typography variant="caption" color="text.secondary">
                {amountText}
              </Typography>
            )}
          </Stack>
        </Box>

        {href ? (
          <Button
            variant="outlined"
            size="small"
            disabled={!archived}
            endIcon={<ArrowForwardOutlined fontSize="small" />}
            component={RouterLink}
            to={href}
          >
            {buttonLabel}
          </Button>
        ) : (
          <Button
            variant="outlined"
            size="small"
            disabled={!archived}
            endIcon={<ArrowForwardOutlined fontSize="small" />}
            onClick={onOpen}
          >
            {buttonLabel}
          </Button>
        )}
      </CardContent>
    </Card>
  );
}
