import { Route, Routes } from "react-router";
import ConfirmEmailPage from "@/features/customer/ConfirmEmailPage";
import ForgotPasswordPage from "@/features/customer/ForgotPasswordPage";
import RegisterPage from "@/features/customer/RegisterPage";
import ResetPasswordPage from "@/features/customer/ResetPasswordPage";
import HomePage from "@/features/session/HomePage";
import LoginPage from "@/features/session/LoginPage";

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<HomePage />} />
      <Route path="/entrar" element={<LoginPage />} />
      <Route path="/cadastro" element={<RegisterPage />} />
      <Route path="/confirmar-email" element={<ConfirmEmailPage />} />
      <Route path="/esqueci-senha" element={<ForgotPasswordPage />} />
      <Route path="/redefinir-senha" element={<ResetPasswordPage />} />
    </Routes>
  );
}
