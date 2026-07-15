import {
  ArchiveOutlined,
  DashboardOutlined,
  DescriptionOutlined,
  GavelOutlined,
  HandshakeOutlined,
  MenuBookOutlined,
  SearchOutlined,
} from "@mui/icons-material";
import {
  AppBar,
  Box,
  Divider,
  Drawer,
  List,
  ListItemButton,
  ListItemIcon,
  ListItemText,
  Toolbar,
  Typography,
} from "@mui/material";
import { NavLink, Outlet } from "react-router-dom";

const drawerWidth = 250;

const navigation = [
  {
    label: "Dashboard",
    icon: <DashboardOutlined />,
    path: "/",
  },
  {
    label: "IRB Protocols",
    icon: <MenuBookOutlined />,
    path: "/irb",
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
              width: 36,
              height: 36,
              borderRadius: 2,
              display: "grid",
              placeItems: "center",
              backgroundColor: "primary.main",
              color: "white",
              fontWeight: 800,
              mr: 1.5,
            }}
          >
            R
          </Box>

          <Box>
            <Typography variant="h6">
              Research Archive Platform
            </Typography>
            <Typography variant="caption" color="text.secondary">
              Legacy research administration data
            </Typography>
          </Box>
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
            Environment
          </Typography>
          <Typography variant="body2" fontWeight={700}>
            Development
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
