# ecommerce-catalog-service

Kotlin / Spring Boot read model for the storefront catalog. Each catalog entry
is a denormalised projection of one product from `product-service` (pulled over
Feign by `POST /api/v1/catalog/sync/{productId}`) plus the merchandising fields
only the catalog owns (`featured`, `imageUrl`, an overridable `price`).

## Storage: MongoDB

This is the one service in the platform that is **not** on PostgreSQL. The
catalog has no joins, no foreign keys and no cross-row invariants — every query
is a single-collection lookup by product id, category, featured flag or name,
and every write touches one document — so it is stored as documents in a
`catalog_entries` collection.

| | |
|---|---|
| Collection | `catalog_entries` |
| Document `_id` | the **product id** — one entry per product |
| `price` | Decimal128 (`spring.data.mongodb.representation.big-decimal=decimal128`), rounded to cents on write |
| Timestamps | `LocalDateTime`, millisecond precision (BSON date) |
| `version` | optimistic lock (`@Version`); a stale PATCH gets **409**, the idempotent sync retries |
| Indexes | `categoryId`, `featured`, `stockStatus` + a weighted **text index** (name 10 / brand 5 / description 1) — created at startup by `CatalogCollectionInitializer` |
| Type hint | `_class` is not written (`MongoConfig`); the initializer strips it from older documents and seeds `version` on them |

Search (`GET /api/v1/catalog/search?q=`) is relevance-ranked `$text` over name,
brand and description; when the text index has no hit (partial words such as
`wirel`, or a blank query) it falls back to the case-insensitive name substring
match the relational version had.

Configuration (all overridable by environment, mirroring the other services'
`SPRING_DATASOURCE_URL` / `DB_USERNAME` / `DB_PASSWORD` convention):

```
SPRING_MONGODB_HOST=catalogdb    SPRING_MONGODB_PORT=27017
SPRING_MONGODB_DATABASE=catalogdb
DB_USERNAME=cataloguser          DB_PASSWORD=catalogpass   (authSource=admin)
SPRING_MONGODB_URI=mongodb://…   (optional; overrides all of the above)
```

The JSON contract is unchanged from the relational version: `id` is still
present and now always equals `productId`.

## Build, test, run

```bash
mvn -B clean package               # unit tests + Testcontainers repository test
mvn -B clean package -Dmaven.test.skip=true
```

`CatalogEntryRepositoryTest` starts a real `mongo:8.0` through Testcontainers
and is **skipped, not failed, when no Docker daemon is reachable**.

Local run against the compose database (`catalogdb` is published on
`localhost:27017`):

```bash
java -jar target/catalog-service-1.0.0-SNAPSHOT.jar
```

Image build (normally done by `ecommerce-platform/build-all.*`; the Dockerfile
copies the pre-built jar):

```bash
docker build -t catalog-service:0.0.1-SNAPSHOT -f docker/Dockerfile .
docker tag catalog-service:0.0.1-SNAPSHOT bmcnpnr/ecommerce-catalog-service:latest
docker push bmcnpnr/ecommerce-catalog-service:latest
```

## Contract tests (Pact)

- `src/test/kotlin/.../contract/ProductServiceConsumerPactTest` — **consumer** of product-service (Feign): `GET /api/v1/products/{id}`, `GET /api/v1/categories`, 404 — driven through `CatalogService.syncFromProductService` with the real Feign client and `FeignConfig`. Writes `target/pacts/catalog-service-product-service.json`, verified in product-service.

Run them alone with `mvn test -Dtest='*PactTest'`; they are ordinary Surefire tests, so `mvn verify` and CI run them too. Regenerate and redistribute pacts across repositories with `ecommerce-platform/sync-pacts.sh` (see its README, "Contract tests").
