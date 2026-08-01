import { ConstructionOutlined } from "@mui/icons-material";
import { Stack, Typography } from "@mui/material";

export function ComingSoonSection({ label }: { label: string }) {
  return (
    <Stack spacing={1.5} sx={{ alignItems: "flex-start", py: 4 }}>
      <ConstructionOutlined color="disabled" fontSize="large" />

      <Typography variant="h6">{label}</Typography>

      <Typography color="text.secondary" sx={{ maxWidth: 480 }}>
        This section will load from the archive once its API endpoint is
        built - not implemented yet, and no data is shown here in the
        meantime.
      </Typography>
    </Stack>
  );
}
