import {
  AccountCircleOutlined,
  ArchiveOutlined,
  DashboardOutlined,
  DescriptionOutlined,
  GavelOutlined,
  HandshakeOutlined,
  LogoutOutlined,
  MenuBookOutlined,
  SearchOutlined,
} from "@mui/icons-material";
import {
  AppBar,
  Box,
  Button,
  Chip,
  Divider,
  Drawer,
  List,
  ListItemButton,
  ListItemIcon,
  ListItemText,
  Stack,
  Toolbar,
  Typography,
} from "@mui/material";
import { useEffect, useState } from "react";
import { NavLink, Outlet } from "react-router-dom";

import { currentUser, logout } from "../auth";

const drawerWidth = 250;

const navigation = [
  {
    label: "Dashboard",
    icon: <DashboardOutlined />,
    path: "/",
  },
  {
    label: "Protocols",
    icon: <MenuBookOutlined />,
    path: "/protocols",
  },
  {
    label: "Awards",
    icon: <ArchiveOutlined />,
    path: "/awards",
  },
  {
    label: "Proposals",
    icon: <DescriptionOutlined />,
    path: "/proposals",
  },
  {
    label: "Negotiations",
    icon: <HandshakeOutlined />,
    path: "/negotiations",
  },
  {
    label: "Subawards",
    icon: <GavelOutlined />,
    path: "/subawards",
  },
  {
    label: "Global Search",
    icon: <SearchOutlined />,
    path: "/search",
  },
];

export function AppLayout() {
  const [signedInUser, setSignedInUser] = useState("Signed in");
  const [signingOut, setSigningOut] = useState(false);

  useEffect(() => {
    let active = true;

    async function loadUser() {
      try {
        const user = await currentUser();

        if (!active) {
          return;
        }

        const displayName =
          user.signInDetails?.loginId ??
          user.username ??
          "Signed in";

        setSignedInUser(displayName);
      } catch {
        if (active) {
          setSignedInUser("Signed in");
        }
      }
    }

    void loadUser();

    return () => {
      active = false;
    };
  }, []);

  async function handleSignOut() {
    try {
      setSigningOut(true);
      await logout();
    } finally {
      setSigningOut(false);
    }
  }

  return (
    <Box sx={{ display: "flex", minHeight: "100vh" }}>
      <AppBar
        position="fixed"
        sx={{
          zIndex: (theme) => theme.zIndex.drawer + 1,
          backgroundColor: "#ffffff",
          color: "#172033",
          borderBottom: "1px solid #e7e9ee",
          boxShadow: "none",
        }}
      >
        <Toolbar>
          <Box
            sx={{
              width: 38,
              height: 38,
              borderRadius: 2,
              display: "grid",
              placeItems: "center",
              backgroundColor: "primary.main",
              color: "white",
              fontWeight: 900,
              mr: 1.5,
            }}
          >
            BU
          </Box>

          <Stack
            sx={{
              width: "100%",
              flexDirection: "row",
              alignItems: "center",
              justifyContent: "space-between",
              gap: 2,
            }}
          >
            <Box>
              <Typography variant="h6">
                Boston University Research Data Hub
              </Typography>

              <Typography variant="caption" color="text.secondary">
                Legacy research administration archive
              </Typography>
            </Box>

            <Stack
              sx={{
                flexDirection: "row",
                alignItems: "center",
                gap: 1.5,
              }}
            >
              <Chip
                label="Development"
                size="small"
                variant="outlined"
                sx={{ display: { xs: "none", md: "flex" } }}
              />

              <Stack
                sx={{
                  display: { xs: "none", lg: "flex" },
                  flexDirection: "row",
                  alignItems: "center",
                  gap: 0.75,
                  maxWidth: 260,
                }}
              >
                <AccountCircleOutlined color="action" />

                <Typography
                  variant="body2"
                  noWrap
                  title={signedInUser}
                >
                  {signedInUser}
                </Typography>
              </Stack>

              <Button
                variant="outlined"
                size="small"
                startIcon={<LogoutOutlined />}
                disabled={signingOut}
                onClick={() => void handleSignOut()}
              >
                {signingOut ? "Signing out..." : "Sign out"}
              </Button>
            </Stack>
          </Stack>
        </Toolbar>
      </AppBar>

      <Drawer
        variant="permanent"
        sx={{
          width: drawerWidth,
          flexShrink: 0,
          "& .MuiDrawer-paper": {
            width: drawerWidth,
            boxSizing: "border-box",
            pt: 9,
            borderRight: "1px solid #e7e9ee",
          },
        }}
      >
        <Typography
          variant="overline"
          color="text.secondary"
          sx={{ px: 3, pt: 2 }}
        >
          Navigation
        </Typography>

        <List sx={{ px: 1.5 }}>
          {navigation.map((item) => (
            <ListItemButton
              key={item.path}
              component={NavLink}
              to={item.path}
              sx={{
                my: 0.4,
                borderRadius: 2,
                "&.active": {
                  backgroundColor: "rgba(139, 24, 50, 0.10)",
                  color: "primary.main",
                  "& .MuiListItemIcon-root": {
                    color: "primary.main",
                  },
                },
              }}
            >
              <ListItemIcon sx={{ minWidth: 42 }}>
                {item.icon}
              </ListItemIcon>

              <ListItemText primary={item.label} />
            </ListItemButton>
          ))}
        </List>

        <Divider sx={{ mt: 2 }} />

        <Box sx={{ p: 3 }}>
          <Typography variant="caption" color="text.secondary">
            Data source
          </Typography>

          <Typography variant="body2" sx={{ fontWeight: 700 }}>
            Kuali Legacy Archive
          </Typography>

          <Typography
            variant="caption"
            color="text.secondary"
            sx={{ display: "block", mt: 1.5 }}
          >
            Current IRB records
          </Typography>

          <Typography variant="body2" sx={{ fontWeight: 700 }}>
            1,852
          </Typography>
        </Box>
      </Drawer>

      <Box
        component="main"
        sx={{
          flexGrow: 1,
          p: { xs: 2, md: 4 },
          mt: 8,
          minWidth: 0,
        }}
      >
        <Outlet />
      </Box>
    </Box>
  );
}
