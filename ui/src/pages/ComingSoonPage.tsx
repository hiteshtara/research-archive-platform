import { ConstructionOutlined } from "@mui/icons-material";
import { Box, Card, CardContent, Stack, Typography } from "@mui/material";

export function ComingSoonPage() {
  return (
    <Box sx={{ maxWidth: 700 }}>
      <Card>
        <CardContent sx={{ p: 5 }}>
          <Stack spacing={2} sx={{ alignItems: "flex-start" }}>
            <ConstructionOutlined color="primary" fontSize="large" />
            <Typography variant="h4">Coming soon</Typography>
            <Typography color="text.secondary">
              This archive module will be added after the IRB experience is
              complete.
            </Typography>
          </Stack>
        </CardContent>
      </Card>
    </Box>
  );
}
