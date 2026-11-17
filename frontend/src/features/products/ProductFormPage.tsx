import { zodResolver } from "@hookform/resolvers/zod";
import { useRef, useState } from "react";
import {
  type Resolver,
  type UseFormReturn,
  type UseFormSetError,
  useForm,
} from "react-hook-form";
import { Link, useNavigate, useParams } from "react-router";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import AdminGuard from "@/features/admin/AdminGuard";
import {
  ProductConflictError,
  ProductForbiddenError,
  ProductNotFoundError,
  ProductUnavailableError,
  ProductValidationError,
} from "./api";
import {
  useCreateProduct,
  useProduct,
  useUpdateProduct,
  useUpdateProductPrice,
} from "./hooks";
import {
  CATEGORY_LABELS,
  NO_PERMISSION_PRICE,
  NO_PERMISSION_PRODUCTS,
  PRODUCT_NOT_FOUND,
  SAVE_ERRORS,
  SERVER_FIELD_MESSAGES,
  SERVER_FIELD_NAMES,
  STATUS_NOTICES,
} from "./messages";
import ProductCoverSection from "./ProductCoverSection";
import ProductFileSection from "./ProductFileSection";
import ProductPublicationSection from "./ProductPublicationSection";
import {
  centsToInput,
  formatCents,
  PRODUCT_CATEGORIES,
  type Product,
  type ProductCategory,
  parsePriceToCents,
  productDataSchema,
  productPriceSchema,
} from "./schemas";

type DataValues = { title: string; description: string; category: string };
type PriceValues = { price: string };
type SaveErrorKind = keyof typeof SAVE_ERRORS;

const dataResolver = zodResolver(productDataSchema) as Resolver<DataValues>;
const priceResolver = zodResolver(productPriceSchema) as Resolver<PriceValues>;

function priceCents(text: string): number {
  const result = parsePriceToCents(text);
  if (!result.ok) throw new Error("Price was validated before");
  return result.cents;
}

// Puts the server's field codes on the form fields. Returns the kind of block error to show, if any.
function describeError(
  error: unknown,
  setError: UseFormSetError<DataValues> | UseFormSetError<PriceValues>,
): SaveErrorKind | null {
  if (error instanceof ProductConflictError) return "conflict";
  if (error instanceof ProductForbiddenError) return "forbidden";
  if (error instanceof ProductUnavailableError) return "unavailable";
  if (error instanceof ProductValidationError) {
    let mapped = false;
    for (const [field, code] of Object.entries(error.errors)) {
      const name = SERVER_FIELD_NAMES[field];
      const message = SERVER_FIELD_MESSAGES[code];
      if (name && message) {
        (setError as UseFormSetError<Record<string, string>>)(name, {
          message,
        });
        mapped = true;
      }
    }
    return mapped ? null : "unexpected";
  }
  return "unexpected";
}

function SaveError({
  kind,
  onReload,
}: {
  kind: SaveErrorKind | null;
  onReload?: () => void;
}) {
  if (!kind) return null;
  return (
    <div className="text-sm text-destructive">
      <p role="alert">{SAVE_ERRORS[kind]}</p>
      {kind === "conflict" && onReload && (
        <Button
          variant="outline"
          type="button"
          className="mt-2"
          onClick={onReload}
        >
          Recarregar
        </Button>
      )}
    </div>
  );
}

function FieldError({ id, message }: { id: string; message?: string }) {
  if (!message) return null;
  return (
    <p id={id} role="alert" className="text-sm text-destructive">
      {message}
    </p>
  );
}

function DataFields({ form }: { form: UseFormReturn<DataValues> }) {
  const { register, formState } = form;
  const errors = formState.errors;
  const fieldDescribedBy = (
    name: "title" | "description" | "category",
    help?: string,
  ) =>
    [help ? `${name}-help` : null, errors[name] ? `${name}-error` : null]
      .filter(Boolean)
      .join(" ") || undefined;

  return (
    <div className="grid gap-4">
      <div className="grid gap-1.5">
        <Label htmlFor="title">Título</Label>
        <Input
          id="title"
          aria-invalid={errors.title ? true : undefined}
          aria-describedby={fieldDescribedBy("title", "help")}
          {...register("title")}
        />
        <p id="title-help" className="text-sm text-muted-foreground">
          Até 120 caracteres.
        </p>
        <FieldError id="title-error" message={errors.title?.message} />
      </div>
      <div className="grid gap-1.5">
        <Label htmlFor="description">Descrição</Label>
        <Textarea
          id="description"
          aria-invalid={errors.description ? true : undefined}
          aria-describedby={fieldDescribedBy("description", "help")}
          {...register("description")}
        />
        <p id="description-help" className="text-sm text-muted-foreground">
          Até 5.000 caracteres.
        </p>
        <FieldError
          id="description-error"
          message={errors.description?.message}
        />
      </div>
      <div className="grid gap-1.5">
        <Label htmlFor="category">Categoria</Label>
        <select
          id="category"
          aria-invalid={errors.category ? true : undefined}
          aria-describedby={fieldDescribedBy("category")}
          className="h-8 w-full min-w-0 rounded-lg border border-input bg-transparent px-2.5 py-1 text-base outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 aria-invalid:border-destructive aria-invalid:ring-3 aria-invalid:ring-destructive/20 md:text-sm dark:bg-input/30"
          {...register("category")}
        >
          <option value="">Selecione uma categoria</option>
          {PRODUCT_CATEGORIES.map((code) => (
            <option key={code} value={code}>
              {CATEGORY_LABELS[code]}
            </option>
          ))}
        </select>
        <FieldError id="category-error" message={errors.category?.message} />
      </div>
    </div>
  );
}

function PriceField({
  form,
  extraHelp,
}: {
  form: UseFormReturn<PriceValues>;
  extraHelp?: boolean;
}) {
  const error = form.formState.errors.price;
  const describedBy = [
    "price-help",
    extraHelp ? "price-help-orders" : null,
    error ? "price-error" : null,
  ]
    .filter(Boolean)
    .join(" ");
  return (
    <div className="grid gap-1.5">
      <Label htmlFor="price">Valor (R$)</Label>
      <Input
        id="price"
        inputMode="decimal"
        autoComplete="off"
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy}
        {...form.register("price")}
      />
      <p id="price-help" className="text-sm text-muted-foreground">
        Use vírgula nos centavos. Exemplo: 19,90. O mínimo é R$ 5,00 e o máximo
        é R$ 9.999,99.
      </p>
      {extraHelp && (
        <p id="price-help-orders" className="text-sm text-muted-foreground">
          Mudar o preço vale só para Pedidos novos; Pedidos já feitos mantêm o
          valor.
        </p>
      )}
      <FieldError id="price-error" message={error?.message} />
    </div>
  );
}

function CreateProduct() {
  const navigate = useNavigate();
  const create = useCreateProduct();
  const dataForm = useForm<DataValues>({
    resolver: dataResolver,
    defaultValues: { title: "", description: "", category: "" },
  });
  const priceForm = useForm<PriceValues>({
    resolver: priceResolver,
    defaultValues: { price: "" },
  });
  const [error, setError] = useState<SaveErrorKind | null>(null);
  // Set synchronously, so a second click before the next render cannot send a second request.
  const sending = useRef(false);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (sending.current) return;
    sending.current = true;
    setError(null);
    const [dataOk, priceOk] = await Promise.all([
      dataForm.trigger(),
      priceForm.trigger(),
    ]);
    if (!dataOk || !priceOk) {
      sending.current = false;
      // Focus goes to the first field with an error, in screen order.
      const firstData = (["title", "description", "category"] as const).find(
        (name) => dataForm.getFieldState(name).invalid,
      );
      if (firstData) dataForm.setFocus(firstData);
      else priceForm.setFocus("price");
      return;
    }
    const data = dataForm.getValues();
    create.mutate(
      {
        title: data.title,
        description: data.description,
        category: data.category as ProductCategory,
        priceCents: priceCents(priceForm.getValues("price")),
      },
      {
        onSuccess: () =>
          navigate("/painel/produtos", { state: { created: true } }),
        onError: (e) => {
          const dataKind = describeError(e, dataForm.setError);
          const priceKind = describeError(e, priceForm.setError);
          setError(dataKind && priceKind ? dataKind : null);
        },
        onSettled: () => {
          sending.current = false;
        },
      },
    );
  }

  return (
    <main className="p-8">
      <h1 className="text-2xl font-bold">Novo Produto</h1>
      <p className="mt-4">
        Este Produto começa como rascunho: os clientes ainda não conseguem vê-lo
        nem comprá-lo.
      </p>
      <form onSubmit={submit} noValidate className="mt-6 grid max-w-xl gap-8">
        <section aria-labelledby="data-heading">
          <h2 id="data-heading" className="mb-4 text-lg font-semibold">
            Dados do produto
          </h2>
          <DataFields form={dataForm} />
        </section>
        <section aria-labelledby="price-heading">
          <h2 id="price-heading" className="mb-4 text-lg font-semibold">
            Preço
          </h2>
          <PriceField form={priceForm} />
        </section>
        <SaveError kind={error} />
        <div className="flex items-center gap-4">
          <Button type="submit" disabled={create.isPending}>
            {create.isPending ? "Criando…" : "Criar Produto"}
          </Button>
          <Link to="/painel/produtos" className="underline">
            Cancelar
          </Link>
        </div>
      </form>
    </main>
  );
}

function EditForms({
  product,
  canChangePrice,
  canPublish,
  reload,
}: {
  product: Product;
  canChangePrice: boolean;
  canPublish: boolean;
  reload: () => Promise<unknown>;
}) {
  const updateData = useUpdateProduct(product.id);
  const updatePrice = useUpdateProductPrice(product.id);
  const dataForm = useForm<DataValues>({
    resolver: dataResolver,
    defaultValues: {
      title: product.title,
      description: product.description,
      category: product.category,
    },
  });
  const priceForm = useForm<PriceValues>({
    resolver: priceResolver,
    defaultValues: { price: centsToInput(product.priceCents) },
  });
  const saving = updateData.isPending || updatePrice.isPending;
  const [dataError, setDataError] = useState<SaveErrorKind | null>(null);
  const [priceError, setPriceError] = useState<SaveErrorKind | null>(null);

  // Fetches the current Product and replaces what is in both blocks with it.
  async function reloadBoth() {
    const fresh = (await reload()) as { isError?: boolean; data?: Product };
    // A failed fetch keeps the conflict warning and what the user typed.
    if (fresh.isError || !fresh.data) return;
    const current = fresh.data;
    dataForm.reset({
      title: current.title,
      description: current.description,
      category: current.category,
    });
    priceForm.reset({ price: centsToInput(current.priceCents) });
    updateData.reset();
    updatePrice.reset();
    setDataError(null);
    setPriceError(null);
  }

  const saveData = dataForm.handleSubmit((values) => {
    setDataError(null);
    updateData.mutate(
      {
        title: values.title,
        description: values.description,
        category: values.category as ProductCategory,
        version: product.version,
      },
      { onError: (e) => setDataError(describeError(e, dataForm.setError)) },
    );
  });

  const savePrice = priceForm.handleSubmit((values) => {
    setPriceError(null);
    updatePrice.mutate(
      { priceCents: priceCents(values.price), version: product.version },
      { onError: (e) => setPriceError(describeError(e, priceForm.setError)) },
    );
  });

  return (
    <main className="p-8">
      <h1 className="text-2xl font-bold">Editar Produto</h1>
      <p className="mt-4">
        <Link to="/painel/produtos" className="underline">
          Voltar para Produtos
        </Link>
      </p>
      <p className="mt-4">{STATUS_NOTICES[product.status]}</p>
      <div className="mt-6 grid max-w-xl gap-8">
        <section aria-labelledby="data-heading">
          <h2 id="data-heading" className="mb-4 text-lg font-semibold">
            Dados do produto
          </h2>
          <form
            onSubmit={saveData}
            onChange={() => updateData.isSuccess && updateData.reset()}
            noValidate
            className="grid gap-4"
          >
            <DataFields form={dataForm} />
            <SaveError kind={dataError} onReload={reloadBoth} />
            {updateData.isSuccess && (
              <p role="status" className="text-sm">
                Dados salvos.
              </p>
            )}
            <div>
              <Button type="submit" disabled={saving}>
                {updateData.isPending ? "Salvando…" : "Salvar dados"}
              </Button>
            </div>
          </form>
        </section>
        <section aria-labelledby="price-heading">
          <h2 id="price-heading" className="mb-4 text-lg font-semibold">
            Preço
          </h2>
          {canChangePrice ? (
            <form onSubmit={savePrice} noValidate className="grid gap-4">
              <PriceField form={priceForm} extraHelp />
              <SaveError kind={priceError} onReload={reloadBoth} />
              {updatePrice.isSuccess && (
                <p role="status" className="text-sm">
                  Preço salvo.
                </p>
              )}
              <div>
                <Button type="submit" disabled={saving}>
                  {updatePrice.isPending ? "Salvando…" : "Salvar preço"}
                </Button>
              </div>
            </form>
          ) : (
            <div className="grid gap-2">
              <p>Valor: {formatCents(product.priceCents)}</p>
              <p className="text-sm text-muted-foreground">
                {NO_PERMISSION_PRICE}
              </p>
            </div>
          )}
        </section>
        <ProductCoverSection product={product} />
        <ProductFileSection product={product} />
        {canPublish && <ProductPublicationSection product={product} />}
      </div>
    </main>
  );
}

function EditProduct({
  id,
  canChangePrice,
  canPublish,
}: {
  id: string;
  canChangePrice: boolean;
  canPublish: boolean;
}) {
  const product = useProduct(id);

  if (product.isPending) {
    return (
      <main className="p-8">
        <p>Carregando…</p>
      </main>
    );
  }
  if (product.isError && product.data === undefined) {
    const notFound = product.error instanceof ProductNotFoundError;
    const forbidden = product.error instanceof ProductForbiddenError;
    return (
      <main className="p-8">
        <p role="alert" className="text-destructive">
          {notFound
            ? PRODUCT_NOT_FOUND
            : forbidden
              ? NO_PERMISSION_PRODUCTS
              : "Não foi possível carregar o Produto agora. Tente novamente."}
        </p>
        <p className="mt-4">
          <Link to="/painel/produtos" className="underline">
            Voltar para Produtos
          </Link>
        </p>
      </main>
    );
  }
  return (
    <EditForms
      product={product.data}
      canChangePrice={canChangePrice}
      canPublish={canPublish}
      reload={product.refetch}
    />
  );
}

export default function ProductFormPage() {
  const { id } = useParams();
  return (
    <AdminGuard
      capability="PRODUCTS_EDIT"
      missingMessage={NO_PERMISSION_PRODUCTS}
    >
      {(admin) =>
        id ? (
          <EditProduct
            id={id}
            canChangePrice={admin.capabilities.includes("PRODUCTS_PRICE")}
            canPublish={admin.capabilities.includes("PRODUCTS_PUBLISH")}
          />
        ) : (
          <CreateProduct />
        )
      }
    </AdminGuard>
  );
}
