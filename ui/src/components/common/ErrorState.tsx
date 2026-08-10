import { Alert } from "@mui/material";

export function ErrorState({
  message,
  onClose,
}: {
  message: string;
  onClose?: () => void;
}) {
  return (
    <Alert severity="error" onClose={onClose}>
      {message}
    </Alert>
  );
}
