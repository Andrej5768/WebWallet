<h1>WebWallet</h1>

My own personal financial project: personal finance tracking in the spirit of
Zen Money combined with investment analytics in the spirit of
Snowball Income. Built for personal use first, but designed
to grow into a public service.

## Product vision

### General
- Multi-user from the start, with security suitable for a public service.
- Clients: web (React + TypeScript, separate repository) and native mobile apps
  (Kotlin and Swift, separate repositories, later).
- Backend (this repository): Spring Boot 4 / Java 21, a pure REST API with an OpenAPI contract.
  The Thymeleaf pages and old controllers will be removed.
- Hosting: own VPS with Docker Compose (backend + PostgreSQL + web).
- Languages: Ukrainian and English.
- Sign-in: email + password, Google / Apple, TOTP two-factor authentication; PIN / biometrics in the mobile apps.
- Home screen: dashboard on the web, transaction feed with quick entry on mobile.
- Notifications: mobile push, Telegram bot, email digest.

### Personal finance (first priority)
- Multi-currency: each account has its own currency; reports in the base currency using NBU exchange rates.
- Account types: cash and cards, credit cards and loans, deposits and savings, debts with people, investment accounts.
- Two-level categories (category → subcategory), tags and payees.
- Transactions: expense, income, transfer (including cross-currency with two amounts), split across several categories.
- Data entry:
  - manual entry;
  - **Monobank** synchronization via its public API;
  - **PrivatBank** PDF statement parser;
  - history import from Zen Money.
- Auto-categorization (payee / MCC rules learned from manual corrections) and deduplication
  of manual entries against bank and imported transactions.
- **Envelope budgeting** (YNAB style): only budget accounts are distributed (deposits and investments
  stay off-budget), the budget is kept in the base currency, balances roll over.
- Planning: recurring payments, balance forecast, financial goals.
- Reports: spending by category, income vs. expenses, net worth over time, spending by payee.

### Investments (Snowball Income analog)
- Assets: stocks and ETFs (US / Europe), bonds including Ukrainian government bonds (OVDP), crypto,
  other assets with manual valuation.
- Trade sources: manual entry, Interactive Brokers (Flex Query), Freedom Finance, Ukrainian brokers;
  crypto from Binance, WhiteBIT (read-only API / CSV), BTC and ETH addresses (including ERC-20 tokens).
- Analytics: dividends (calendar, forecast, yield), portfolio returns (profit, XIRR / TWR, benchmark),
  allocation (sectors, countries, currencies, asset classes), rebalancing to target weights.
- Market data behind a provider interface: free sources first (Finnhub / Alpha Vantage, CoinGecko,
  NBU for exchange rates), a paid provider later for the public version.
- Ukrainian taxes: tax on dividends with credit for foreign withholding tax, tax on investment profit
  (NBU rates on trade dates), annual declaration summary. Tax rates are configurable per year.

### How finances and investments connect
- A brokerage or crypto account is an `INVESTMENT` account in finances: it appears in the account list
  and in net worth, but stays outside the envelopes.
- Moving money from a card to a broker is a transfer, not an expense.
- Trades live only in the investment module; the investment account balance equals cash plus the market
  value of positions.
- Dividends and coupons belong to the investment module and are shown as passive income in the
  income vs. expenses report (a projection, not a duplicate record).

## Getting started

Requirements: JDK 21, Maven, Docker.

Run PostgreSQL and Mailpit in Docker and the backend from the IDE or Maven with the `local` profile:

```bash
docker compose up -d postgres mailpit
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

The `local` profile is in `config/application-local.properties` and is read from the project root (the working
directory); it is not packaged into the jar.

Or run everything in containers:

```bash
cp .env.example .env   # set JWT_SECRET, ENCRYPTION_KEY (`openssl rand -base64 32`) and DB_PASSWORD
docker compose --profile app up -d --build
```

- API: http://localhost:8080/api/v1, Swagger UI: http://localhost:8080/swagger-ui.html (enabled by the `local`
  profile or `API_DOCS_ENABLED=true`)
- Emails sent by the backend (verification, password reset): http://localhost:8025
- Tests: `mvn verify` (needs Docker for Testcontainers)
- Formatting: `mvn spotless:apply` (palantir-java-format); CI fails on unformatted code (`mvn spotless:check`)

Configuration is done with environment variables: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`,
`ENCRYPTION_KEY` (encrypts bank tokens), `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM`,
`APP_FRONTEND_URL`, `CORS_ALLOWED_ORIGINS`, `APP_PUBLIC_URL` (makes the Monobank `WEBHOOK` mode available), `TRUSTED_PROXIES`
(a regular expression of reverse proxy addresses allowed to set `X-Forwarded-For`, loopback by default),
`API_DOCS_ENABLED`, `REFRESH_COOKIE_SECURE`
(see `src/main/resources/application.properties`).

### Authentication

- `POST /api/v1/auth/login` returns the access token (JWT, 15 minutes) in the body and sets the refresh token
  (30 days) in the `refresh_token` cookie: `HttpOnly`, `Secure`, `SameSite=Strict`, `Path=/api/v1/auth`.
  The refresh token never appears in a response body, so scripts of the web client cannot read it.
- Requests to the rest of the API send the access token in `Authorization: Bearer <token>`.
- `POST /api/v1/auth/refresh` takes the cookie (no body), rotates the refresh token and returns a new access
  token; a missing, unknown or already rotated token is rejected with 401 `INVALID_REFRESH_TOKEN`, and presenting
  a rotated token ends the whole session.
- `POST /api/v1/auth/logout` ends the session of the cookie and deletes the cookie.
- The `/api/v1/auth/**` endpoints ignore the `Authorization` header, so a client may send its expired or invalid
  access token along: refresh and logout still work (otherwise the token would be rejected with 401 before the
  cookie is looked at, and a logout would not end the session).
- `POST /api/v1/me/password` ends all sessions and starts a new one the same way as login.
- The web client is served from the same domain as the API (a reverse proxy sends `/api` to the backend), so the
  browser sends the cookie without CORS. The `local` profile drops the `Secure` flag
  (`app.auth.refresh-cookie-secure=false`, or `REFRESH_COOKIE_SECURE=false`) for development over plain HTTP.
- The native mobile apps will need a separate way to keep the refresh token; it is not part of the API yet.

### Deployment

The web client and the API are served from one origin by a reverse proxy: `/api` goes to the backend, everything
else is the static build of the web client with a fallback to `index.html` for the routes of the single-page app.
The proxy adds the security headers; the web client loads no third-party scripts, styles or fonts, so the content
security policy allows only the own origin (`style-src 'unsafe-inline'` is for inline style attributes, `data:`
images for inline icons). An nginx example:

```nginx
server {
    listen 443 ssl;
    http2 on;
    server_name wallet.example.com;
    # ssl_certificate ...; ssl_certificate_key ...;

    root /usr/share/nginx/html;  # the build of the web client

    add_header Content-Security-Policy "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'" always;
    add_header Referrer-Policy "same-origin" always;
    add_header X-Content-Type-Options "nosniff" always;
    add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
    add_header Permissions-Policy "camera=(), microphone=(), geolocation=(), payment=(), usb=()" always;

    location /api/ {
        proxy_pass http://backend:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        client_max_body_size 20m;  # statement and export uploads
    }

    # Hashed file names of the build never change
    location /assets/ {
        add_header Cache-Control "public, max-age=31536000, immutable";
        # add_header in a location replaces the headers of the server block, so repeat them
        add_header Content-Security-Policy "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'" always;
        add_header Referrer-Policy "same-origin" always;
        add_header X-Content-Type-Options "nosniff" always;
        add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
        add_header Permissions-Policy "camera=(), microphone=(), geolocation=(), payment=(), usb=()" always;
        try_files $uri =404;
    }

    location / {
        try_files $uri $uri/ /index.html;
    }
}

server {
    listen 80;
    server_name wallet.example.com;
    return 301 https://$host$request_uri;
}
```

Set `TRUSTED_PROXIES` to the address of the proxy, so that the rate limits see the client addresses from
`X-Forwarded-For`.

### API conventions

- `PUT` of accounts, categories and tags keeps the values of omitted optional fields. The `icon`, `color` and
  `necessity` of a category and the `color` of a tag are removed by sending them as `null`; a credit limit or interest
  rate of an account is removed by sending `0`. The parent of a category is always applied (`null` = top level).
- Categories and tags have a `color`, one of `blue`, `green`, `orange`, `violet`, `teal`, `pink`, `amber`, `slate`,
  `red`, `indigo`, `cyan`, `lime` (the client picks the shade for the theme). Categories also have an `icon`
  (a [Lucide](https://lucide.dev/icons) name in kebab case, e.g. `shopping-cart`) and, for expense categories,
  `necessity`: `NEEDS` or `WANTS`; `null` on a subcategory means the same as its parent.
- `PUT /api/v1/categories/order` takes `[{id, parentId, sortOrder}]` after drag and drop, applies all items or none
  (two levels, kinds of the parent) and returns the tree like `GET /api/v1/categories`.
- `GET /api/v1/transactions?q=...` searches the payee name, the comment and split comments, ignoring case; a number
  (`1284.50`, `1284,50`, `1 284,50`) also matches the amount. It combines with the other filters and paging.
- Accounts have `balanceInBase` (today's NBU rate, `null` without a rate) and, for credit cards with a limit,
  `availableCredit`. `GET /api/v1/accounts/summary` returns `netWorth`, `available` (cash and cards),
  `availableWithCredit` and `readyToAssign` in the base currency, with `unconvertedCurrencies` like the reports.
- The last name of a user is optional (`null` when not given).
- Budget: in `GET /api/v1/budget/{month}` the `assigned`, `activity` and `available` of a parent category row already
  include its subcategories (own + children); `ownAssigned`, `ownActivity` and `ownAvailable` are the values of the
  parent's own envelope (for a subcategory the same as the totals). `POST /api/v1/budget/{month}/move` with
  `{fromCategoryId, toCategoryId, amount}` (two different expense categories of the user, `amount` > 0) lowers the own
  assignment of the source and raises that of the target in one transaction (the source may go below zero, like a
  negative assignment). `POST /api/v1/budget/{month}/copy-previous` (no body) gives every expense category whose own
  assignment this month is zero its own assignment of the previous month; assigned envelopes and archived categories
  are left as they are. Both return the month like `GET`.
- Typing in the OpenAPI spec (OpenAPI 3.1): every field of a response schema is `required`, because every field is
  always written, also when it is `null`; a field that may be `null` has `"type": ["<type>", "null"]` (an enum also
  lists `null`, a nested object is `oneOf` the object and `null`). The request fields that clear a value when sent
  as `null` are typed the same way: `parentId`, `icon`, `color` and `necessity` of a category (and `parentId` in the
  order), the `color` of a tag, the `accountId` of a Monobank account link, the `lastName` of the profile and
  `categoryId`, `tagIds` and `comment` of an import confirmation. Uploaded files are `type: string, format: binary`.
- Monobank synchronization schedule: `GET /api/v1/banks/monobank` returns `syncMode` (`INTERVAL`, `DAILY` or
  `WEBHOOK`), `windowStart`, `windowEnd` and `dailyAt` (Kyiv local times, `HH:mm`) and `nextSyncAt`.
  `INTERVAL` (the default) synchronizes every 15 minutes from `windowStart` to `windowEnd` (08:00–23:00 by default)
  and once at night, halfway between the end and the next start of the window; `DAILY` once a day at `dailyAt`
  (06:00 by default); `WEBHOOK` registers the bank webhook (only possible with `APP_PUBLIC_URL`) and also checks the
  accounts once a day at `dailyAt`. The webhook is removed at the bank in the other modes. The schedule is set with
  `POST /api/v1/banks/monobank` (optional, a reconnect keeps it) or `PUT /api/v1/banks/monobank/schedule`
  (`syncMode` required, omitted times kept; the window must start before it ends). A synchronization requests every
  linked account once; Monobank allows one request a minute per token, so with many accounts it takes several minutes
  and the next one is scheduled after it is complete. `nextSyncAt` in the past means a synchronization (e.g. loading
  the history after connecting) is in progress.
- Import preview: `POST /api/v1/imports/privatbank/preview` (multipart `file`, optional `accountId`),
  `POST /api/v1/imports/zenmoney/preview` (`{token}`) and `POST /api/v1/imports/zenmoney/file/preview` (multipart
  `file`) parse the input and run the import in a transaction that is rolled back, so nothing is saved. They return
  `{id, source, expiresAt, summary, operations}`: `summary` is the `ImportResult` that importing everything as
  previewed would give (accounts, categories, payees created, transactions imported or skipped as duplicates,
  budgets, warnings) and `operations` the first page (100) of the operations, newest first: `key`, `date`, `payee`,
  `amount`, `currency`, `account` and `toAccount` (`{id, name, currency, created}`; `id` is `null` when the import
  creates the account), `toAmount`, `type` (`EXPENSE`, `INCOME`, `TRANSFER`), `categoryId` and `categoryName` (the
  suggested category; `categoryId` is `null` for a category the import creates), `tagIds` (empty), `comment` and
  `duplicate`. A Zen Money export may hold tens of thousands of operations, so they are paged:
  `GET /api/v1/imports/previews/{id}?page=0&size=100` (at most 500). The parsed records (never the file or a token)
  are kept in the database for an hour. `POST /api/v1/imports/previews/{id}/confirm` with
  `{operations: [{key, include, type, transferAccountId, categoryId, keepSuggestedCategory, tagIds, comment,
  rememberRule}]}` imports through the same importers as the direct endpoints and returns the `ImportResult`;
  operations not listed are imported as previewed, a listed one gets exactly the given category, tags and comment
  (`null` = none) unless `keepSuggestedCategory: true`, which ignores `categoryId` and keeps the previewed suggestion,
  also a category the import creates (`categoryId` `null` and `categoryName` set in the preview), `include: false`
  leaves it out, an expense or income may become a transfer to or from `transferAccountId` (same currency), and `rememberRule`
  creates a categorization rule for the payee before the import. `DELETE /api/v1/imports/previews/{id}` discards a
  preview; an unknown or expired one is 404 `NOT_FOUND`. A user keeps at most 3 previews.
- Start over: `POST /api/v1/me/reset` with `{keepCategories, confirm: "DELETE"}` (any other `confirm` is 400) deletes
  all accounts, transactions, payees, tags, the budget, bank connections and import previews of the user in one
  transaction and answers 204. With `keepCategories: false` the categories and categorization rules are deleted too
  and the default categories are created again; with `true` they stay, and so do the payees the rules refer to. The
  profile, the password and the sessions stay. It is a `POST` to an action rather than a `DELETE`, because it takes
  a body and leaves the user with fresh default data.

### OpenAPI spec

The spec is committed in `api/openapi.json`; the web client generates its API client from it and downloads it from
the `dev` branch: https://raw.githubusercontent.com/Andrej5768/WebWallet/dev/api/openapi.json.

`OpenApiSpecTest` compares the file with the spec served at `/v3/api-docs` (sorted keys, without `servers`) and
fails when the API changed without updating it; the current spec is then written to `target/openapi.json`.
Regenerate the file and commit it together with the API change:

```bash
mvn test -Dtest=OpenApiSpecTest -Dopenapi.update=true
```

## Development workflow

- `dev` is the default branch and holds ongoing development.
- `master` holds the stable version and is what gets deployed.
- Each task is done in its own branch created from `dev` (e.g. `feature/monobank-sync`, `fix/...`)
  and merged into `dev` through a pull request.
- A release is a pull request from `dev` into `master`. `master` is changed only through pull requests.
- GitHub Actions runs `mvn verify` (tests and the 75% coverage check) on pushes and pull requests
  to both branches; a pull request is merged only when the build is green.

## Roadmap

### Phase 0 — Backend foundation
- [x] Flyway migrations with a new baseline schema, `ddl-auto=validate`
- [x] Integration tests on PostgreSQL via Testcontainers instead of H2
- [x] REST API under `/api/v1` with an OpenAPI spec (springdoc) and ProblemDetail errors
- [x] Stateless JWT authentication (access token + rotating refresh token in an HttpOnly cookie);
      registration, email confirmation and password reset as API endpoints
- [x] Remove Thymeleaf pages, session-based login and the old controllers
- [x] Dockerfile and Docker Compose (backend + PostgreSQL + Mailpit)
- [x] Ukrainian and English localization only

### Phase 1 — Finance MVP (enough to move off Zen Money)
- [x] New domain model: accounts, two-level categories, tags, payees, transactions with transfer legs
      and splits, money as amount + ISO currency
- [x] Daily NBU exchange rates
- [x] Import pipeline: parsing → normalization → deduplication → auto-categorization
- [x] Monobank synchronization (personal token stored encrypted, statements, webhook, MCC)
- [x] PrivatBank PDF statement parser
- [x] Zen Money export import
- [x] Auto-categorization rules
- [x] Envelope budget: assigned / activity / available per month and category, roll-over,
      overspending, credit card payments
- [x] Reports: spending by category, income vs. expenses, net worth, payees

### Phase 2 — Web client (separate repository)
- [ ] React + TypeScript app with an API client generated from the OpenAPI spec
      (committed in `api/openapi.json`)
- [ ] Dashboard, transaction feed, envelopes, reports, imports, goals, settings
      (mockups: light and dark themes, Ukrainian and English)
- [ ] Backend changes the web client needs:
  - [x] icon, color and a needs / wants flag on categories (for 50/30/20 analytics);
        reordering and moving categories between groups
  - [x] color on tags
  - [x] free-text transaction search (payee, comment, amount)
  - [x] last name optional at registration
  - [x] account balances in the base currency and totals (net worth, available money, ready to assign)
  - [x] import preview: parse a statement or a Zen Money export without saving, let the user
        change type, category and tags of each operation, then import the reviewed list
  - [x] Monobank synchronization schedule: every 15 minutes within a time window (default),
        once a day, or webhook (opt-in, as it costs the most)
  - [x] "start over": delete all accounts, transactions, envelopes and bank connections,
        optionally keeping categories and rules
  - [x] moving money between envelopes and copying last month's assignments in one request;
        own values of a parent envelope; exact required / nullable typing in the OpenAPI spec

### Phase 3 — Planning
- [ ] Recurring payments (reminders or automatic creation)
- [ ] Balance forecast
- [ ] Savings goals: target amount and date, linked account or Monobank jar, monthly amount needed,
      on-track / behind status
- [ ] 50/30/20 analytics based on the needs / wants flag of categories
- [ ] Analytics endpoints for the reports:
  - [ ] plan vs actual per envelope with an end-of-month forecast from the current pace
  - [ ] period comparison: cumulative spending by day for two periods (month to month, year to year,
        custom), optionally up to the same day, and the change per category
  - [ ] spending trends: monthly amounts per category over a range, averages and anomalies
  - [ ] savings: net saved and cumulative savings per month, savings rate against a target

### Phase 4 — Investments
- [ ] Instruments, positions, trades, dividends and coupons, corporate actions (splits)
- [ ] Market data provider abstraction with free providers
- [ ] Imports: Interactive Brokers, Freedom Finance, Ukrainian brokers, Binance, WhiteBIT, BTC / ETH addresses
- [ ] Analytics: dividend calendar and forecast, XIRR / TWR, benchmark, allocation, rebalancing
- [ ] Financial independence: FIRE number (lean / regular / fat / coast) and years to reach it,
      FYM (months of expenses covered by liquid money), compound interest calculator

### Phase 5 — Ukrainian taxes
- [ ] Dividend tax with foreign withholding credit
- [ ] Investment profit tax using NBU rates on trade dates
- [ ] Annual declaration summary

### Phase 6 — Notifications and mobile
- [ ] Telegram bot (notifications and quick expense entry)
- [ ] Email digest
- [ ] Push notifications (FCM / APNs)
- [ ] Native Android (Kotlin) and iOS (Swift) apps with PIN / biometrics

### Phase 7 — Public release readiness
- [ ] Google / Apple sign-in, TOTP two-factor authentication
- [ ] Rate limiting, encryption of bank tokens at rest
- [ ] User data export and deletion
- [ ] Database backups and monitoring

## License

This project is source-available under the [PolyForm Noncommercial License 1.0.0](LICENSE.md).
Personal and other noncommercial use is allowed. Commercial use requires a separate agreement with the author.
