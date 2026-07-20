import {
  ArrowForwardOutlined,
  SearchOutlined,
} from "@mui/icons-material";

import {
  Alert,
  Box,
  Card,
  CardContent,
  Chip,
  CircularProgress,
  InputAdornment,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  TextField,
  Typography,
} from "@mui/material";

import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { useNavigate } from "react-router-dom";

import { getAwardFamilies } from "../api/client";

export function AwardFamiliesPage() {

  const navigate = useNavigate();

  const [search, setSearch] = useState("");

  const [appliedSearch, setAppliedSearch] =
    useState("");

  const query = useQuery({

    queryKey: [
      "award-families",
      appliedSearch,
    ],

    queryFn: () =>
      getAwardFamilies({
        query: appliedSearch,
        limit: 100,
      }),

  });

  return (
    <Stack spacing={3}>

      <Card>

        <CardContent>

          <Typography variant="h4">
            Award Families
          </Typography>

          <Typography
            color="text.secondary"
            sx={{ mt: 1 }}
          >
            One row per Award Number.
          </Typography>

          <TextField
            fullWidth
            sx={{ mt: 3 }}
            placeholder="Award number, sponsor, title..."

            value={search}

            onChange={(event) =>
              setSearch(
                event.target.value,
              )
            }

            onKeyDown={(event) => {

              if (event.key === "Enter") {

                setAppliedSearch(
                  search.trim(),
                );

              }

            }}

            slotProps={{
              input: {
                startAdornment: (
                  <InputAdornment position="start">
                    <SearchOutlined />
                  </InputAdornment>
                ),
              },
            }}

          />

        </CardContent>

      </Card>

      <Card>

        {query.isLoading && (

          <Box
            sx={{
              display: "grid",
              placeItems: "center",
              py: 8,
            }}
          >
            <CircularProgress />
          </Box>

        )}

        {query.isError && (

          <Alert severity="error">

            Unable to load Award families.

          </Alert>

        )}

        {query.data && (

          <>

            <Box sx={{ px: 3, py: 2 }}>

              <Typography sx={{ fontWeight: 700 }}>

                {query.data.length.toLocaleString()} Award Families

              </Typography>

            </Box>

            <TableContainer>

              <Table>

                <TableHead>

                  <TableRow>

                    <TableCell>Award Number</TableCell>

                    <TableCell>Sequence</TableCell>

                    <TableCell>Status</TableCell>

                    <TableCell>Sponsor</TableCell>

                    <TableCell>Lead Unit</TableCell>

                    <TableCell>Account</TableCell>

                    <TableCell>Title</TableCell>

                    <TableCell />

                  </TableRow>

                </TableHead>

                <TableBody>

                  {query.data.map((award) => (

                    <TableRow

                      key={award.awardNumber}

                      hover

                      sx={{
                        cursor: "pointer",
                      }}

                      onClick={() =>

                        navigate(

                          `/awards/history/${encodeURIComponent(
                            award.awardNumber,
                          )}`

                        )

                      }

                    >

                      <TableCell>

                        <Typography
                          sx={{ fontWeight: 700 }}
                        >
                          {award.awardNumber}
                        </Typography>

                      </TableCell>

                      <TableCell>

                        <Chip
                          size="small"
                          label={
                            award.latestSequenceNumber
                          }
                        />

                      </TableCell>

                      <TableCell>

                        {award.status}

                      </TableCell>

                      <TableCell>

                        {award.sponsor}

                      </TableCell>

                      <TableCell>

                        {award.leadUnit}

                      </TableCell>

                      <TableCell>

                        {award.accountNumber ?? "—"}

                      </TableCell>

                      <TableCell
                        sx={{ maxWidth: 420 }}
                      >

                        <Typography
                          noWrap
                          variant="body2"
                        >
                          {award.title}
                        </Typography>

                      </TableCell>

                      <TableCell align="right">

                        <ArrowForwardOutlined
                          color="action"
                        />

                      </TableCell>

                    </TableRow>

                  ))}

                </TableBody>

              </Table>

            </TableContainer>

          </>

        )}

      </Card>

    </Stack>
  );

}
