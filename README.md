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
`APP_FRONTEND_URL`, `CORS_ALLOWED_ORIGINS`, `APP_PUBLIC_URL` (enables bank webhooks), `TRUSTED_PROXIES`
(a regular expression of reverse proxy addresses allowed to set `X-Forwarded-For`, loopback by default),
`API_DOCS_ENABLED`
(see `src/main/resources/application.properties`).

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
- [x] Stateless JWT authentication (access + rotating refresh tokens) for the web and mobile clients;
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
- [ ] Dashboard, transaction feed, envelopes, reports, imports

### Phase 3 — Planning
- [ ] Recurring payments (reminders or automatic creation)
- [ ] Balance forecast
- [ ] Financial goals

### Phase 4 — Investments
- [ ] Instruments, positions, trades, dividends and coupons, corporate actions (splits)
- [ ] Market data provider abstraction with free providers
- [ ] Imports: Interactive Brokers, Freedom Finance, Ukrainian brokers, Binance, WhiteBIT, BTC / ETH addresses
- [ ] Analytics: dividend calendar and forecast, XIRR / TWR, benchmark, allocation, rebalancing

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
