import { Navigate, Route, Routes } from "react-router-dom";

import { AppLayout } from "./layout/AppLayout";

import { AwardFamiliesPage } from "./pages/AwardFamiliesPage";
import { AwardHistoryPage } from "./pages/AwardHistoryPage";
import { AwardDashboardPage } from "./pages/award/AwardDashboardPage";
import { AwardHierarchyPage } from "./pages/award/AwardHierarchyPage";
import { AwardSearchPage } from "./pages/award/AwardSearchPage";

import { ComingSoonPage } from "./pages/ComingSoonPage";
import { DashboardPage } from "./pages/DashboardPage";
import { GlobalSearchPage } from "./pages/GlobalSearchPage";
import { InvestigatorProfilePage } from "./pages/InvestigatorProfilePage";
import { IrbDetailPage } from "./pages/IrbDetailPage";
import { IrbHistoryDetailPage } from "./pages/IrbHistoryDetailPage";
import { IrbPage } from "./pages/IrbPage";
import { NegotiationFamiliesPage } from "./pages/NegotiationFamiliesPage";
import { NegotiationWorkspacePage } from "./pages/NegotiationWorkspacePage";
import { ProposalFamiliesPage } from "./pages/ProposalFamiliesPage";
import { ProposalWorkspacePage } from "./pages/ProposalWorkspacePage";
import { SubawardFamiliesPage } from "./pages/SubawardFamiliesPage";
import { SubawardWorkspacePage } from "./pages/SubawardWorkspacePage";

export default function App() {
  return (
    <Routes>
      <Route element={<AppLayout />}>

        <Route index element={<DashboardPage />} />

        <Route path="irb" element={<IrbPage />} />
        <Route
          path="irb/families"
          element={<Navigate to="/irb" replace />}
        />
        <Route
          path="irb/history"
          element={<Navigate to="/irb" replace />}
        />
        <Route
          path="irb/history/:protocolId"
          element={<IrbHistoryDetailPage />}
        />
        <Route
          path="irb/record/:recordId"
          element={<IrbDetailPage />}
        />

        <Route
          path="protocols"
          element={<Navigate to="/irb" replace />}
        />
        <Route
          path="protocols/:protocolNumber"
          element={<Navigate to="/irb" replace />}
        />

        <Route
          path="awards"
          element={<AwardFamiliesPage />}
        />

        <Route
          path="awards/history/:awardNumber"
          element={<AwardHistoryPage />}
        />

        <Route
          path="awards/search"
          element={<AwardSearchPage />}
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
          element={<ComingSoonPage />}
        />

        <Route
          path="search"
          element={<GlobalSearchPage />}
        />

        <Route
          path="investigators/:email"
          element={<InvestigatorProfilePage />}
        />

        <Route
          path="*"
          element={<Navigate to="/" replace />}
        />

      </Route>
    </Routes>
  );
}
