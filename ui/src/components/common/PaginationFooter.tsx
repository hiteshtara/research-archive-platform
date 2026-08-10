import { Box, Pagination } from "@mui/material";

// Matches the Pagination block repeated across Award's paginated
// sections (AwardVersionsSection, AwardAmountsSection, Budget's
// sub-panels, Time & Money's tables, AwardSapTransmissionsSection),
// Proposal's ProposalVersionsSection, and AwardSearchPage - zero-based
// `page` in, one-based display, zero-based page back out. Owns only
// presentation and the zero/one-based translation; the caller still
// owns its own useState/useQuery page wiring.
export function PaginationFooter({
  totalPages,
  page,
  onPageChange,
  disabled,
}: {
  totalPages: number;
  page: number;
  onPageChange: (zeroBasedPage: number) => void;
  disabled?: boolean;
}) {
  if (totalPages <= 1) {
    return null;
  }

  return (
    <Box sx={{ display: "flex", justifyContent: "center" }}>
      <Pagination
        count={totalPages}
        page={page + 1}
        onChange={(_event, value) => onPageChange(value - 1)}
        disabled={disabled}
      />
    </Box>
  );
}
