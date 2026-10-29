import { Route, Routes } from "react-router";
import { Button } from "@/components/ui/button";
import RegisterPage from "@/features/customer/RegisterPage";

function Home() {
  return (
    <main className="p-8">
      <h1 className="text-2xl font-bold">sapiensia</h1>
      <Button className="mt-4">Funciona</Button>
    </main>
  );
}

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<Home />} />
      <Route path="/cadastro" element={<RegisterPage />} />
    </Routes>
  );
}
