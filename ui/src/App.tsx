import { Navigate, Route, Routes } from "react-router-dom";

import { AppLayout } from "./layout/AppLayout";

import { AwardDashboardPage } from "./pages/award/AwardDashboardPage";
import { AwardHierarchyPage } from "./pages/award/AwardHierarchyPage";
import { AwardSearchPage } from "./pages/award/AwardSearchPage";
import { AwardVersionSearchPage } from "./pages/award/AwardVersionSearchPage";

import { ArchivedFileFinderPage } from "./pages/ArchivedFileFinderPage";
import { DashboardPage } from "./pages/DashboardPage";
import { DocumentsPage } from "./pages/DocumentsPage";
import { ExplorerPage } from "./pages/ExplorerPage";
import { GlobalSearchPage } from "./pages/GlobalSearchPage";
import { NegotiationFamiliesPage } from "./pages/NegotiationFamiliesPage";
import { NegotiationWorkspacePage } from "./pages/NegotiationWorkspacePage";
import { ProposalDiscoveryPage } from "./pages/ProposalDiscoveryPage";
import { ProposalFamiliesPage } from "./pages/ProposalFamiliesPage";
import { ProposalDashboardPage } from "./pages/proposal/ProposalDashboardPage";
import { ProposalWorkspacePage } from "./pages/ProposalWorkspacePage";
import { SubawardFamiliesPage } from "./pages/SubawardFamiliesPage";
import { SubawardWorkspacePage } from "./pages/SubawardWorkspacePage";

export default function App() {
  return (
    <Routes>
      <Route element={<AppLayout />}>

        <Route index element={<DashboardPage />} />

        {/* Retired: the legacy Award Families/History pages predated
            the current Award Search/Dashboard experience and bypassed
            it entirely (raw award_number grouping mislabeled as
            "families", no hierarchy/comments/contacts/Time & Money).
            Redirected rather than 404ing so no old bookmark/link
            strands a user - see docs/kuali-business-rules for the
            current Award object model these routes now lead into. */}
        <Route
          path="awards"
          element={<Navigate to="/awards/search" replace />}
        />

        <Route
          path="awards/history/:awardNumber"
          element={<Navigate to="/awards/search" replace />}
        />

        <Route
          path="awards/search"
          element={<AwardSearchPage />}
        />

        <Route
          path="awards/versions/search"
          element={<AwardVersionSearchPage />}
        />

        <Route
          path="awards/hierarchy/:awardNumber"
          element={<AwardHierarchyPage />}
        />

        <Route
          path="awards/:awardId"
          element={<AwardDashboardPage />}
        />

        <Route
          path="proposals"
          element={<ProposalFamiliesPage />}
        />

        <Route
          path="proposals/:proposalNumber"
          element={<ProposalWorkspacePage />}
        />

        <Route
          path="proposals/dashboard/:proposalId"
          element={<ProposalDashboardPage />}
        />

        <Route
          path="negotiations"
          element={<NegotiationFamiliesPage />}
        />

        <Route
          path="negotiations/:negotiationId"
          element={<NegotiationWorkspacePage />}
        />

        <Route
          path="subawards"
          element={<SubawardFamiliesPage />}
        />

        <Route
          path="subawards/:subawardId"
          element={<SubawardWorkspacePage />}
        />

        <Route
          path="documents"
          element={<DocumentsPage />}
        />

        <Route
          path="archived-files"
          element={<ArchivedFileFinderPage />}
        />

        <Route
          path="explorer"
          element={
            import.meta.env.VITE_EXPLORER_ENABLED === "true" ? (
              <ExplorerPage />
            ) : (
              <Navigate to="/" replace />
            )
          }
        />

        <Route
          path="explorer/proposals"
          element={
            import.meta.env.VITE_EXPLORER_ENABLED === "true" ? (
              <ProposalDiscoveryPage />
            ) : (
              <Navigate to="/" replace />
            )
          }
        />

        <Route
          path="search"
          element={<GlobalSearchPage />}
        />

        <Route
          path="*"
          element={<Navigate to="/" replace />}
        />

      </Route>
    </Routes>
  );
}
