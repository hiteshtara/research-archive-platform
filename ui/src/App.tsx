import { Navigate, Route, Routes } from "react-router-dom";

import { AppLayout } from "./layout/AppLayout";
import { ComingSoonPage } from "./pages/ComingSoonPage";
import { DashboardPage } from "./pages/DashboardPage";
import { IrbDetailPage } from "./pages/IrbDetailPage";
import { IrbPage } from "./pages/IrbPage";

export default function App() {
  return (
    <Routes>
      <Route element={<AppLayout />}>
        <Route index element={<DashboardPage />} />
        <Route path="irb" element={<IrbPage />} />
        <Route path="irb/:studyId" element={<IrbDetailPage />} />

        <Route path="awards" element={<ComingSoonPage />} />
        <Route path="proposals" element={<ComingSoonPage />} />
        <Route path="negotiations" element={<ComingSoonPage />} />
        <Route path="subawards" element={<ComingSoonPage />} />
        <Route path="documents" element={<ComingSoonPage />} />
        <Route path="search" element={<ComingSoonPage />} />

        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  );
}
