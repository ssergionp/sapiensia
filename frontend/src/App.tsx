import { Route, Routes } from "react-router";
import ConfirmEmailPage from "@/features/customer/ConfirmEmailPage";
import RegisterPage from "@/features/customer/RegisterPage";
import HomePage from "@/features/session/HomePage";
import LoginPage from "@/features/session/LoginPage";

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<HomePage />} />
      <Route path="/entrar" element={<LoginPage />} />
      <Route path="/cadastro" element={<RegisterPage />} />
      <Route path="/confirmar-email" element={<ConfirmEmailPage />} />
    </Routes>
  );
}
