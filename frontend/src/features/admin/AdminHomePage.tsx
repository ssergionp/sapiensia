import AdminGuard from "./AdminGuard";
import AuthenticatorReplacementSection from "./AuthenticatorReplacementSection";
import RecoveryCodesSection from "./RecoveryCodesSection";

// Products, Orders and "Sair" live in the panel bar (AdminNav), rendered by the AdminGuard.
export default function AdminHomePage() {
  return (
    <AdminGuard>
      {(admin) => (
        <main className="page-container">
          <div className="max-w-3xl">
            <h1 className="text-3xl">Painel do Administrador</h1>
            <p className="mt-4 text-muted-foreground">
              Você entrou como{" "}
              <span className="font-semibold text-foreground [overflow-wrap:anywhere]">
                {admin.email}
              </span>
              .
            </p>
            <RecoveryCodesSection />
            <AuthenticatorReplacementSection
              oldCodeRequired={admin.oldCodeRequired}
            />
          </div>
        </main>
      )}
    </AdminGuard>
  );
}
