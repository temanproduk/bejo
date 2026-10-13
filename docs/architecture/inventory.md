# Products & stock

> Moved out of `CLAUDE.md` unchanged to keep that file small — it is loaded into every session.
> `CLAUDE.md` keeps the rules and gotchas; this is the detail behind them.

The product catalog, categories, stock opname and the stock-change ledger.

## Products & inventory

Basic per-business SKU catalog with simple inventory, scoped by `business_id` (resolved from the authenticated username via `BusinessContext`):
- `ProductCategoryController` → `ProductCategoryService` — categories (`product_categories`); names unique per merchant. Deleting a category first **un-categorizes** its products (`ProductRepository.clearCategory` sets their `category_id` to null in the service, so behavior matches under both Flyway/Postgres and the H2 test schema) then removes the row — products are never deleted with it.
- `ProductController` → `ProductService` — products (`products`); `sku` unique per merchant. Editable fields include an optional `shortDescription`. `stock` is a plain column (inventory management = `PATCH` the value; no ledger). A product's `categoryId` must belong to the same merchant (else 400). `photo` is an optional base64 image validated ≤500 KB decoded; **list responses omit the photo** to keep payloads small, the single-product detail includes it. Prices are `BIGINT` IDR; `costPrice` is optional.
- **Stock opname** — `POST /api/stock-mutations/adjustments` (`ProductService.adjustStock`) bulk-sets several products' stock to their newly counted quantities. Atomic and two-pass (unknown product → 400, nothing changes); returns a per-product variance report (`previousStock`/`newStock`/`difference`). No ledger is persisted.

## Stock mutations (repurposed "purchases")

`StockMutationController` → `StockMutationService` back a unified **stock-change history** in the `stock_mutations` + `stock_mutation_items` tables. Every recorded stock change is a mutation with a `type` (`StockMutationType`): `PURCHASE` (restock), `ADJUSTMENT` (stock opname), `UPDATE` (a product edit that changed stock). Each line snapshots the product (`sku`/`name`) plus `previous_stock`/`new_stock`/`quantity_change`; `unit_price`/`subtotal` are only set for PURCHASE. `stock_mutation_items.product_id` has no FK, so history survives a product edit/delete.

- `POST /api/stock-mutations` — records a **PURCHASE** (restock): atomic two-pass (unknown product → 400, nothing changes), raises each product's `stock`, `total_amount` = Σ subtotals (unit price = provided, else `costPrice`, else 0).
- **ADJUSTMENT** and **UPDATE** mutations are written by `ProductService` after it applies the change: `adjustStock` (stock opname) records one ADJUSTMENT mutation; `update` records an UPDATE mutation only when the edit actually changed `stock`. Both call `StockMutationService.recordStockChange`.
- `GET /api/stock-mutations` lists all mutations (newest first) **with their line items** (batched in one query, no N+1); `GET /api/stock-mutations/{id}` returns a single mutation with its items.
