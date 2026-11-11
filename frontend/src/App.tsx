import { Route, Routes } from "react-router";
import AdminHomePage from "@/features/admin/AdminHomePage";
import AdminLoginPage from "@/features/admin/AdminLoginPage";
import AdminMfaPage from "@/features/admin/AdminMfaPage";
import SetAdminPasswordPage from "@/features/admin/SetAdminPasswordPage";
import CatalogPage from "@/features/catalog/CatalogPage";
import ConfirmEmailPage from "@/features/customer/ConfirmEmailPage";
import ForgotPasswordPage from "@/features/customer/ForgotPasswordPage";
import RegisterPage from "@/features/customer/RegisterPage";
import ResetPasswordPage from "@/features/customer/ResetPasswordPage";
import ProductFormPage from "@/features/products/ProductFormPage";
import ProductListPage from "@/features/products/ProductListPage";
import HomePage from "@/features/session/HomePage";
import LoginPage from "@/features/session/LoginPage";

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<HomePage />} />
      <Route path="/entrar" element={<LoginPage />} />
      <Route path="/catalogo" element={<CatalogPage />} />
      <Route path="/cadastro" element={<RegisterPage />} />
      <Route path="/confirmar-email" element={<ConfirmEmailPage />} />
      <Route path="/esqueci-senha" element={<ForgotPasswordPage />} />
      <Route path="/redefinir-senha" element={<ResetPasswordPage />} />
      <Route path="/painel" element={<AdminHomePage />} />
      <Route path="/painel/entrar" element={<AdminLoginPage />} />
      <Route path="/painel/verificacao" element={<AdminMfaPage />} />
      <Route path="/painel/definir-senha" element={<SetAdminPasswordPage />} />
      <Route path="/painel/produtos" element={<ProductListPage />} />
      <Route path="/painel/produtos/novo" element={<ProductFormPage />} />
      <Route path="/painel/produtos/:id" element={<ProductFormPage />} />
    </Routes>
  );
}
