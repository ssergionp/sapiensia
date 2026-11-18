import { Route, Routes } from "react-router";
import SiteHeader from "@/components/SiteHeader";
import AdminHomePage from "@/features/admin/AdminHomePage";
import AdminLoginPage from "@/features/admin/AdminLoginPage";
import AdminMfaPage from "@/features/admin/AdminMfaPage";
import SetAdminPasswordPage from "@/features/admin/SetAdminPasswordPage";
import AdminOrdersPage from "@/features/admin-orders/AdminOrdersPage";
import CatalogPage from "@/features/catalog/CatalogPage";
import CatalogProductPage from "@/features/catalog/CatalogProductPage";
import ConfirmEmailPage from "@/features/customer/ConfirmEmailPage";
import ForgotPasswordPage from "@/features/customer/ForgotPasswordPage";
import RegisterPage from "@/features/customer/RegisterPage";
import ResetPasswordPage from "@/features/customer/ResetPasswordPage";
import LegalPage from "@/features/legal/LegalPage";
import {
  PRIVACY_POLICY,
  REFUND_POLICY,
  TERMS_OF_USE,
} from "@/features/legal/legalTexts";
import SiteFooter from "@/features/legal/SiteFooter";
import LibraryPage from "@/features/library/LibraryPage";
import OrderPage from "@/features/orders/OrderPage";
import OrdersPage from "@/features/orders/OrdersPage";
import ProductFormPage from "@/features/products/ProductFormPage";
import ProductListPage from "@/features/products/ProductListPage";
import HomePage from "@/features/session/HomePage";
import LoginPage from "@/features/session/LoginPage";

export default function App() {
  return (
    // full-height column: the footer sits at the bottom even on short pages
    <div className="flex min-h-dvh flex-col">
      <a
        href="#conteudo"
        className="sr-only focus:not-sr-only focus:absolute focus:top-2 focus:left-2 focus:z-50 focus:rounded-md focus:bg-primary focus:px-4 focus:py-2 focus:font-semibold focus:text-primary-foreground"
      >
        Pular para o conteúdo
      </a>
      <SiteHeader />
      <div id="conteudo" tabIndex={-1} className="flex-1 outline-none">
        <Routes>
          <Route path="/" element={<HomePage />} />
          <Route path="/entrar" element={<LoginPage />} />
          <Route path="/catalogo" element={<CatalogPage />} />
          <Route path="/catalogo/:id" element={<CatalogProductPage />} />
          <Route path="/biblioteca" element={<LibraryPage />} />
          <Route path="/pedidos" element={<OrdersPage />} />
          <Route path="/pedidos/:id" element={<OrderPage />} />
          <Route path="/cadastro" element={<RegisterPage />} />
          <Route path="/confirmar-email" element={<ConfirmEmailPage />} />
          <Route path="/esqueci-senha" element={<ForgotPasswordPage />} />
          <Route path="/redefinir-senha" element={<ResetPasswordPage />} />
          <Route path="/painel" element={<AdminHomePage />} />
          <Route path="/painel/entrar" element={<AdminLoginPage />} />
          <Route path="/painel/verificacao" element={<AdminMfaPage />} />
          <Route
            path="/painel/definir-senha"
            element={<SetAdminPasswordPage />}
          />
          <Route path="/painel/pedidos" element={<AdminOrdersPage />} />
          <Route path="/painel/produtos" element={<ProductListPage />} />
          <Route path="/painel/produtos/novo" element={<ProductFormPage />} />
          <Route path="/painel/produtos/:id" element={<ProductFormPage />} />
          <Route
            path="/termos-de-uso"
            element={<LegalPage document={TERMS_OF_USE} />}
          />
          <Route
            path="/privacidade"
            element={<LegalPage document={PRIVACY_POLICY} />}
          />
          <Route
            path="/reembolso"
            element={<LegalPage document={REFUND_POLICY} />}
          />
        </Routes>
      </div>
      <SiteFooter />
    </div>
  );
}
