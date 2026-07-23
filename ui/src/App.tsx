import { Navigate, Route, Routes } from "react-router-dom";

import { AppLayout } from "./layout/AppLayout";

import { AwardFamiliesPage } from "./pages/AwardFamiliesPage";
import { AwardHistoryPage } from "./pages/AwardHistoryPage";

import { ComingSoonPage } from "./pages/ComingSoonPage";
import { DashboardPage } from "./pages/DashboardPage";
import { GlobalSearchPage } from "./pages/GlobalSearchPage";
import { InvestigatorProfilePage } from "./pages/InvestigatorProfilePage";
import { IrbDetailPage } from "./pages/IrbDetailPage";
import { IrbFamiliesPage } from "./pages/IrbFamiliesPage";
import { IrbHistoryDetailPage } from "./pages/IrbHistoryDetailPage";
import { IrbHistoryPage } from "./pages/IrbHistoryPage";
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
        <Route path="irb/families" element={<IrbFamiliesPage />} />
        <Route path="irb/history" element={<IrbHistoryPage />} />
        <Route
          path="irb/history/:protocolId"
          element={<IrbHistoryDetailPage />}
        />
        <Route
          path="irb/record/:recordId"
          element={<IrbDetailPage />}
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
