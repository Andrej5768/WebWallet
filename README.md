<h1>WebWallet</h1>

My own personal financial project: personal finance tracking combined with investment analytics and Ukrainian taxes.
Built for personal use first, but designed to grow into a public service.

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
- Account types: cash and cards, credit cards and loans, deposits and savings, debts with people, investment accounts,
  accounts of a sole proprietor (ФОП).
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

### Investments
- Assets: stocks and ETFs (US / Europe), bonds including Ukrainian government bonds (OVDP), crypto,
  other assets with manual valuation.
- Trade sources: manual entry, Interactive Brokers (Flex Query), Freedom Finance, Ukrainian brokers;
  crypto from Binance, WhiteBIT (read-only API / CSV), BTC and ETH addresses (including ERC-20 tokens).
- Analytics: dividends (calendar, forecast, yield), portfolio returns (profit, XIRR / TWR, benchmark),
  allocation (sectors, countries, currencies, asset classes), rebalancing to target weights.
- Market data behind a provider interface: free sources first (Finnhub / Alpha Vantage, CoinGecko,
  NBU for exchange rates), a paid provider later for the public version.
- Ukrainian taxes: tax on dividends with credit for foreign withholding tax, tax on investment profit
  (NBU rates on trade dates), annual declaration summary; the single tax, military levy and social contribution of a
  sole proprietor by quarter. Tax rates are configurable per year.

### How finances and investments connect
- A brokerage or crypto account is an `INVESTMENT` account in finances: it appears in the account list
  and in net worth, but stays outside the envelopes.
- Moving money from a card to a broker is a transfer, not an expense. Money a broker reports on its own (e.g. a
  card payment at the broker, possibly in another currency than the account) is a deposit or withdrawal operation of
  the investment account; both count as the money invested.
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
`APP_FRONTEND_URL`, `CORS_ALLOWED_ORIGINS`, `TWELVE_DATA_API_KEY` and `ALPHA_VANTAGE_API_KEY` (market data and dividends, see below), `APP_PUBLIC_URL` (makes the Monobank `WEBHOOK` mode available), `TRUSTED_PROXIES`
(a regular expression of reverse proxy addresses allowed to set `X-Forwarded-For`, loopback by default),
`API_DOCS_ENABLED`, `REFRESH_COOKIE_SECURE`, `TELEGRAM_BOT_TOKEN`, `TELEGRAM_BOT_USERNAME` and
`TELEGRAM_WEBHOOK_SECRET` (the Telegram bot, see below), `APP_DEMO_ENABLED` and `APP_DEMO_TTL` (the demo sandbox
opened without registration, on by default and deleted after a day), `APP_ANDROID_MIN_VERSION_CODE`,
`APP_ANDROID_LATEST_VERSION_CODE` and `APP_ANDROID_STORE_URL` (the versions of the Android app the server supports,
returned by `GET /api/v1/app/config`)
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
- Native apps send `X-WebWallet-Client: android` (or `ios`) and `X-App-Version` on every request and keep their
  tokens themselves: `login`, `login/two-factor`, `refresh`, `restore`, `google/complete` and `me/password` return
  the refresh token in `refreshToken` and set no cookie; `refresh` and `logout` take `{refreshToken}` in the body
  (the body is looked at first, then the cookie, for every client; a native request without either is rejected
  with 401). Rotation, reuse detection and the 30-day lifetime are the same, and a token presented by a client of
  another kind (a browser cookie replayed by an app or the other way round) ends the session as reuse would. Which
  client keeps a token is stored with it (`refresh_tokens.client`, null for the web). The trusted device of a
  two-factor sign-in works the same way: `login/two-factor` with `trustDevice` returns `trustedDeviceToken` in the
  body instead of the cookie, and `login` and `google/complete` take it back as `trustedDeviceToken`.

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
- Native apps send `X-WebWallet-Client` (`android`, `ios`) and `X-App-Version` (the `versionCode`) on every request;
  the web client sends neither. The server logs the client with every line and error alert. Before signing in the
  apps read `GET /api/v1/app/config` (public): the supported versions of the Android app and which optional features
  (Google sign-in, market search, Telegram, push) are on.
- Google sign-in from the apps: `POST /api/v1/auth/google/native {idToken, trustedDeviceToken?}` takes the ID token
  the app got from Google with the web client of `GET /api/v1/auth/options` (`googleWebClientId`) as the server
  client, and answers like the browser flow; `POST /api/v1/me/google/link/native {idToken, password?, code?}` links
  the account after the identity check. `GOOGLE_ANDROID_CLIENT_IDS` lists extra audiences the tokens may carry.
- `Idempotency-Key` (a UUID chosen by the client) on a `POST` that creates something (accounts, transactions and their
  batch, investment operations, goals and contributions, recurring payments, categories, tags, payees, rules, import
  confirmations, budget moves): the same request sent again within a day gets the stored response back with
  `Idempotency-Replayed: true` instead of creating a second resource; the same key with another request is `409`
  `IDEMPOTENCY_MISMATCH`. Only successful responses are stored, so a request that failed can be corrected and sent
  again under its key. The header is meant for the apps retrying after a lost response; the web client does not send it.
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
- Broker reports: `POST /api/v1/imports/freedom/preview` (multipart `files`: the trades report and the cash flows
  report of Freedom Finance as XLSX, in any order, one of each at most; optional `accountId`, else the brokerage
  account named Freedom Finance, created when there is none) runs the import in a transaction that is rolled back and
  returns `{id, source, expiresAt, account, from, to, instruments, operations, cash, merged, providerUnavailable}`:
  every row of the reports, newest first, as `NEW`, `DUPLICATE` (imported before, by the number of the trade or the
  operation) or `SKIPPED` (`SERVICE`: blocking and unblocking money for a withdrawal; `UNKNOWN`), the money per
  currency and the quantity of each ticker after the import, and the instrument suggested for each ticker (one the user
  already holds, else a listing of the provider on the market of the ticker suffix such as `.US`, else one to create
  by hand, e.g. a fund of the Moscow exchange). A trade of a currency pair (`EUR/USD`) is an `EXCHANGE`, a card
  payment or bank transfer a `DEPOSIT` or `WITHDRAWAL`, a dividend takes the tax withheld on the same security, and a
  trade takes its fee from the cash flows (the trades report does not name its currency; a fee charged in another
  currency than the trade's is a `FEE` of its own). `POST /api/v1/imports/freedom/previews/{id}/confirm` with
  `{instruments: [{ticker, instrumentId | symbol + micCode | manual}], excluded: [key]}` imports it and returns
  `{accountId, accountCreated, instrumentsCreated, operationsImported, duplicates}`; it is rejected (400) when the
  reports sell more than they buy before. `GET /api/v1/imports/previews/{id}` is 404 for these previews;
  `DELETE` discards them.
- Start over: `POST /api/v1/me/reset` with `{keepCategories, confirm: "DELETE"}` (any other `confirm` is 400) deletes
  all accounts, transactions, payees, tags, the budget, bank connections and import previews of the user in one
  transaction and answers 204. With `keepCategories: false` the categories and categorization rules are deleted too
  and the default categories are created again; with `true` they stay, and so do the payees the rules refer to. The
  profile, the password and the sessions stay. It is a `POST` to an action rather than a `DELETE`, because it takes
  a body and leaves the user with fresh default data.
- Analytics (`/api/v1/analytics`, base currency, refunds reduce the spending, transfers left out, with
  `unconvertedCurrencies` like the reports): `GET /plan/{month}` compares the envelopes (`planned` = assigned,
  `actual` = spending, `difference`, `completion`) per top-level expense category with nested subcategories and
  forecasts the end of the month: for the current month the spending so far, plus the everyday spending (without
  the payments of recurring payments) continued at its pace (`daysElapsed` of `daysInMonth`), plus `plannedRemaining`:
  the pending occurrences of recurring payments in the category this month; for a past month the actual and for a
  future one the plan.
  `GET /compare?fromA&toA&fromB&toB` (each at most 366 days) returns the cumulative spending by day of both periods
  (`null` after the end of the shorter one) and per top-level category `a`, `b`, `change = a - b` and
  `changeShare = change / b`, largest change first. `GET /trends?from&to` (`yyyy-MM`, by default the last 12
  complete months, at most 36) returns the spending of every month per top-level category with the average and the
  anomalies: months above 1.7 times the average of the other months and at least 2% of the average monthly total.
  `GET /savings?from&to&target` (months, by default the last 12 including the current one; `target` 0..1, 0.2 by
  default) returns income, expense, net, savings rate and cumulative net per month, the money moved from budget
  accounts to accounts outside the budget (minus money moved back) per account, the savings rate of the complete
  months together and the best month. `GET /needs-wants?from&to` (by default the current month) splits the spending
  by the `necessity` of the categories (a subcategory without one takes its parent's; no category or no flag is
  `unclassified`) and returns needs, wants and savings (income minus all spending) with their share of the income
  and the targets 0.5 / 0.3 / 0.2.
- Recurring payments: `GET/POST /api/v1/recurring`, `GET/PUT/DELETE /api/v1/recurring/{id}` (`PUT` replaces all
  fields). A payment has the fields of a transaction (`type`, `accountId`, `amount`, `toAccountId` and `toAmount` of a
  transfer, `categoryId`, `payeeId` or `payeeName`, `comment`) and a schedule: `frequency` (`DAILY`, `WEEKLY`,
  `MONTHLY`, `YEARLY`), `interval` (every N, 1 by default), `startDate` and an optional `endDate`; the dates are counted
  from the start, so a payment on the 31st falls on the last day of shorter months. `nextDate` is the earliest
  occurrence neither paid nor skipped (`null` once the schedule has ended). An occurrence is paid in one of four ways:
  a transaction of the same type on the account (between the same accounts for a transfer) within `tolerance` percent
  of the amount (10 by default, 25 with `variableAmount`) and up to 5 days from the date (fewer for weekly and daily
  payments) is matched automatically, the same payee first, then the closest amount and date; a transaction with
  another payee and another category is never matched. `POST .../occurrences/{date}/link` with `{transactionId}`
  links an existing transaction (`GET .../occurrences/{date}/candidates` offers those of the same type within 10 days
  and half the amount); `POST .../occurrences/{date}/confirm` (optional `{date, amount, toAmount}`) creates the
  transaction; with `autoCreate` it is created on the due date (only for dates from the day the payment was created
  on). `POST .../skip` skips one, `DELETE .../occurrences/{date}` makes it pending again (a linked transaction stays)
  and stops matching and creating it automatically; so does deleting the transaction that paid it. Matching runs
  before the occurrences are read and every hour, also with transactions entered ahead of time (up to a year). `GET /api/v1/recurring/occurrences?from&to` lists the occurrences
  of all payments by date (by default from 31 days ago to 31 days ahead, at most 400 days) with `status` (`PENDING`,
  `PAID`, `SKIPPED`), `overdue` and `transactionId`; pending ones overdue for more than 31 days are dropped. A
  transaction that pays an occurrence has `recurring: {id, name, dueDate}`.
- Balance forecast: `GET /api/v1/forecast?to=` (by default the end of the month, at most 366 days ahead) returns the
  balance of cash and cards (like `available` of the summary) in the base currency for every day from the first of
  the month: `days: [{date, balance, actual}]`, actual up to today, then today's balance minus `dailySpending` (the
  average expenses of the last 90 days, or since the first transaction, without the payments of recurring payments)
  plus the pending occurrences (`events`: `{date, recurringId, name, amount, overdue, categoryId}`, an overdue one is
  expected tomorrow) and transactions entered ahead of time; `lowest` is the lowest day ahead. Transfers between cash
  and cards do not change it.
- Savings goals: `GET /api/v1/goals` returns `{currency, saved, target, monthlyNeeded, goals}` (totals in the base
  currency); `POST`, `GET/PUT/DELETE /api/v1/goals/{id}` (`PUT` replaces all fields; omitted `achieved` and
  `sortOrder` keep their values). A goal has `name`, `targetAmount`, `currency` (by default that of the account, or the
  base currency), optional `targetDate`, `icon` (Lucide) and `color`, and either `accountId` (e.g. a Monobank jar: its
  balance at today's rate is the progress) or `saved`, kept by hand with `POST /api/v1/goals/{id}/contributions`
  `{amount}` (negative takes money out). The pace is linear from the progress when the goal was set (`startDate`,
  `startAmount`; again when it moves to another account) to the target on the deadline: `expected` by today,
  `monthsLeft` (a started month counts), `monthlyNeeded` and `status`: `ACHIEVED` (marked with `achieved: true`, or
  the target is reached), `NO_DATE`, `ON_TRACK`, `BEHIND` (more than 3% of the target below the pace) or `OVERDUE`.
  When the progress cannot be converted (an account in a currency without a rate), it counts as zero, the pace is not
  judged (`ON_TRACK`, no `expected`) and the currency is in `unconvertedCurrencies`. A contribution may not take the
  saved amount below zero or to 15 digits.
- Investments (`/api/v1/investments`): operations of `INVESTMENT` accounts (`GET/POST /operations`,
  `GET/PUT/DELETE /operations/{id}`): `BUY` and `SELL` (`quantity`, `price`, `amount` = quantity × price by default,
  `fee`), `DIVIDEND` and `COUPON` (gross `amount`, withheld `tax`), `FEE` (no instrument needed), `SPLIT`
  (`splitFrom` into `splitTo`, each at most 1000), and, without an instrument, `DEPOSIT` and `WITHDRAWAL` (money
  brought to or taken from the account in any currency, e.g. a card payment at the broker; they count as deposited)
  and `EXCHANGE` (`amount` plus `fee` of `currency` for `toAmount` of another `toCurrency`), in the currency of the
  instrument (else of the account) by default; a trade must be in the currency of its instrument, and a dividend,
  coupon, fee or movement of money must be more than zero. Every purchase is a lot; a
  sale takes the oldest lots first (FIFO) and may not sell more than is held on its date, also after an edit or a
  deletion of an earlier operation (400); the operations of an account are changed one at a time. The fee of a
  purchase is part of the cost of its lot. Quantities keep 8 decimals, also after a split. Market instruments
  (stocks, ETFs, funds) are found with `GET /instruments/search?q=` (own and known ones first, then, for at least 2
  characters, the market data provider) and added with `POST /instruments/market` `{symbol, micCode}`; they are
  shared and their data come from the provider. The provider quota is shared by everyone: its results are cached for
  an hour, and a user may make 30 searches an hour that reach it (429 `TOO_MANY_REQUESTS` beyond). A user may read a
  market instrument (`GET /instruments/{id}`, `/prices`) only when they have operations of it, it is a benchmark, or
  they found or added it within the last day, as the instruments show what other users hold. Instruments entered by
  hand (`POST/PUT/DELETE /instruments`, e.g. Ukrainian government bonds) are valued `NOMINAL` (nominal plus the
  coupon accrued since the last payment; coupons are counted back from `maturityDate`, `couponFrequency` times a year:
  1, 2, 3, 4, 6 or 12) or `MANUAL` (`manualPrice`); their dates are between 1990 and 2100. Daily closes come from
  Twelve Data (free plan, key in `TWELVE_DATA_API_KEY`; without it prices are entered by hand): a task checks a few
  instruments a minute, each every 6 hours, loading the history back to the first operation (for a benchmark the
  first deposit to a brokerage account; at least a year), unadjusted, as the operations record splits and dividends;
  the last known close is loaded again, as it may have been the price during the session. `GET /portfolio?accountId=`
  returns the value, `deposited` (the money brought to the accounts minus the money taken away by transfers and
  deposit and withdrawal operations, at the rates of their dates), cost (`invested`), unrealized profit, realized
  profit of the year (FIFO, at the rates of the sale dates), dividends of the last 12 months after tax, the change of
  the day, the positions (average price, price, value, share), the cash per account and currency (the transfers of the
  ledger plus the cash of the operations) and the allocation by type, currency and sector (`bySector`, of the value
  of the positions: GICS sectors, a fund by its sector weights with the rest as `OTHER`, bonds as `BONDS`, `UNKNOWN`
  until the sectors are loaded), in the base currency (or `currency`) at today's rates; a position without a price yet
  is valued at cost. `GET /positions?accountId&instrumentId` returns the lots
  (with the unrealized profit of the open ones), the sales with the lots they took and the operations.
  `GET /history?accountId&from&to&currency&benchmark` returns the value day by day (weekly over 400 days) at the
  closes and NBU rates of the day, from the first day with money on the accounts (an earlier `from` starts there;
  dates between 1990 and 2100, at most 20 years), the net deposits (transfers from and to other accounts, opening
  balances) and, with `benchmark` `SP500` (SPY), `MSCI_WORLD` (URTH) or `NASDAQ100` (QQQ), what the same deposits
  would be worth in the fund bought and sold at the closes of their days (deposits before its first known close are
  bought at that close; price only, dividends not reinvested). `returns` are computed from every day of the period:
  `twr` (time-weighted: the daily returns chained, so deposits and their timing do not count), `twrAnnualized` (for a
  year or longer), `xirr` (money-weighted, per year: the rate at which the value at the start and the deposits grow to
  the value at the end; for 30 days or longer), and the same for the benchmark (`benchmarkTwr`, `benchmarkXirr`), all as
  shares in the currency of the history. The balance of an
  `INVESTMENT` account is its ledger balance plus the cash of its operations and the value of its positions at today's
  prices and rates; the net worth report still counts only the ledger for past months.
- Taxes (`/api/v1/taxes`, in hryvnias at the NBU rates of the dates). `GET/PUT /settings/{year}`: the rates of a
  year — the group of the sole proprietor (1, 2, 3; `fopVat` for the lower rate of a VAT payer), the single tax and the
  military levy (shares of the income for group 3, amounts a month for groups 1 and 2), the minimum social contribution
  (`esvEnabled`, `esvRate` of `minWage`), the personal income tax and military levy of investment income, and whether a
  dividend has the document of the tax withheld abroad by default. `PUT` replaces the user's own values; `null` takes
  the default of the year, which the response returns too (`defaults`) with links to the sources (`sources`): the
  defaults are those known when the year was added and must be checked. `GET /fop/{year}` returns the quarters (income,
  single tax, military levy, social contribution, the deadlines of the declaration, the taxes and the contribution —
  a weekend moves them to Monday, public holidays are not known — and whether the user marked them paid with
  `PUT /fop/{year}/quarters/{quarter}`), the totals, the income limit of the group and every income: of `FOP`
  accounts, the part of incomes on any account in the categories chosen with `PUT /income-categories` (their
  subcategories too), and transactions added with `PUT /transactions/{id}` `{included}` (`false` takes one out,
  `null` forgets the mark; `GET /transactions/{id}` tells whether one counts and why); transfers between own accounts
  are not income. `GET /investments/{year}` returns the
  dividends and coupons (the tax withheld abroad reduces the personal income tax when the document exists:
  `PUT /investments/dividends/{operationId}` `{document}`), the sales (proceeds at the rate of the sale, the lots sold
  by FIFO at the rates of their purchases, so a change of the exchange rate is profit too), the loss carried over
  from earlier years, the taxable profit, the taxes and the deadlines (declaration by 1 May, payment by 31 July of the
  next year). Government bonds are exempt.
- Declarations (`/api/v1/taxes`): `GET/PUT /profile` keeps the taxpayer details the declarations need (tax number,
  name, address, the tax office with its region and district codes, the main activity of a sole proprietor),
  encrypted. `GET /declarations/fop/{year}/{quarter}` writes the declaration of a sole proprietor of group 3 (form
  F0103309, amounts from the start of the year; the fourth quarter adds F0133109 with the social contribution of the
  year, both in a ZIP), `GET /declarations/property/{year}` the yearly declaration of property and income (F0100215:
  foreign dividends on line 10.10 with the tax paid abroad on line 18, the investment profit on line 10.8, with
  F0121215 — Ф1 — for the sales, in a ZIP). The files follow the formats of the State Tax Service (windows-1251, its
  file names, linked appendices) and are validated against its schemas in the tests; they are opened, checked and
  signed in the tax service cabinet. The tick boxes of the yearly declaration are those of a resident filling it in
  personally, of the general category.
- Dividend calendar: `GET /api/v1/investments/dividends?accountId&from&to&currency` (by default a year back and a year
  ahead, dates between 1990 and 2100, at most 5 years) lists the payments by date with a `status`: `RECEIVED` (the `DIVIDEND` and `COUPON`
  operations), `EXPECTED` (due to the holder on the ex-dividend date but not recorded), `DECLARED` (from the dividend
  provider, or the coupon schedule of a bond: nominal × rate / frequency × quantity, computed rather than stepped
  through, so a far maturity costs nothing) and `FORECAST` (the payments of
  the last 12 months repeated a year later for what is held now, after the last declared one). A recorded dividend
  dated from 5 days before the ex-dividend date to 31 days after the payment date stands for the declared one. The tax
  of the coming ones is estimated from the last dividend of the instrument (`taxEstimated`). It also returns the gross
  and net payments of the next 12 months, the monthly average, the yield (on the value and on the cost of the
  positions), the payments per month by status and what each instrument paid over the last 12 months; the totals cover
  the 12 months around today whatever the range. Dividends come from Alpha Vantage (free key in
  `ALPHA_VANTAGE_API_KEY`, about 25 requests a day): the US-dollar stocks, ETFs and funds someone holds are checked
  once a week, one an hour. The same hourly request loads the sectors when no dividends are due: of a company from its
  SEC industry (`OVERVIEW`), of a fund its weights (`ETF_PROFILE`), checked every 90 days.
- Telegram bot (`TELEGRAM_BOT_TOKEN` and `TELEGRAM_BOT_USERNAME` from @BotFather; off without them). With
  `TELEGRAM_WEBHOOK_SECRET` the backend registers the webhook `<APP_PUBLIC_URL>/api/v1/telegram/webhook` at start and
  checks the secret in the `X-Telegram-Bot-Api-Secret-Token` header; without it, it polls Telegram (local development —
  use a separate test bot, a bot cannot have a webhook and be polled at once). `POST /api/v1/telegram/link` (with the current password, see below) returns a
  one-time `t.me/<bot>?start=<code>` link (15 minutes) that connects the chat, `GET /api/v1/telegram` the state,
  `PUT /api/v1/telegram/settings` `{quickEntryAccountId}`, `DELETE /api/v1/telegram` disconnects (so does `/stop` in
  the bot). A message to the bot is recorded right away: the first number is the amount (`+` for an income), words
  naming accounts pick the account (compared in Latin letters, so «моно» names Monobank; a word fitting several is
  asked with buttons), two of them make a transfer, `вчора` or `dd.mm` the date, the rest is the comment; the category
  comes from the rules and the payee, the latest transaction with that comment, or a category of that name. The
  buttons under it change the category or the account, turn an expense into a transfer or cancel it, for 48 hours.
  Commands: `/balance`, `/today`, `/month`, `/planned` (30 days of planned payments, the taxes of a sole proprietor
  and the day the forecast goes below zero), `/help`, `/stop`. Notifications (`GET/PUT /api/v1/notifications/settings`:
  a switch per kind and the `hour` of Kyiv time) go once each, at that hour: recurring payments due tomorrow and today
  (with «Paid», which records the payment), envelopes at 80 % and 100 % of the month, taxes of a sole proprietor 7 days
  and a day ahead, the first day of the next 30 the forecast is below zero (again only when it changes), goals
  reached, on Mondays after the first week the spending of the month so far against the same days of the previous
  month and on the 1st the previous month against the month before, both with a PNG chart; a Monobank token the bank
  rejects is reported within 10 minutes, except from 22:00 to 8:00.
- Email digest: the weekly and the monthly report of the Telegram bot also go by email (HTML with the chart attached
  inline and a plain text version), at the same hour, to every confirmed address unless `emailWeekly` / `emailMonthly`
  of `/api/v1/notifications/settings` are off. The weekly one adds the envelopes at 80 % or more, the payments of 7 days
  and the forecast, the monthly one the goals. Every email has `List-Unsubscribe` with `List-Unsubscribe-Post`
  (RFC 8058): `POST /api/v1/notifications/unsubscribe?user&token` switches both off without signing in, `GET` of it
  redirects to the page `/unsubscribe` of the web client, `POST /api/v1/notifications/resubscribe` switches them on.
  The token is an HMAC of the user id with the JWT key, so nothing is stored.
- Changes of how the account is reached — linking Google (`POST /api/v1/me/google/link`), connecting Telegram and
  starting two-factor authentication (`POST /api/v1/me/two-factor/setup`) — take `{password, code}`: the current
  password, or for an account without one a two-factor code when it is on, so that a stolen session cannot give its
  thief a way back in. A new or reset password disconnects the Telegram chat. Failed logins block an IP for an account
  after 10 tries and the account for every IP after 30; two-factor codes are limited to 10 per 15 minutes and 30 a
  day. The webhook secret of a Monobank connection is stored as its hash, and the CSV of the data export neutralizes
  cells that a spreadsheet would run as formulas.
- Market data providers: the services use `MarketDataProvider` (instruments and daily closes), `DividendProvider`
  (dividends) and `SectorProvider` (sectors, from the same service as the dividends); the implementation is chosen with `app.market-data.prices-provider` (`twelve-data`) and
  `app.market-data.dividends-provider` (`alpha-vantage`). Another service is another implementation of the interface;
  instruments are identified by the ticker and the ISO 10383 market identifier code of the exchange.

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
- `master` holds the stable version.
- Each task is done in its own branch created from `dev` (e.g. `feature/monobank-sync`, `fix/...`)
  and merged into `dev` through a pull request.
- A release is a pull request from `dev` into `master`. `master` is changed only through pull requests.
- While the first version is being built, finished steps are pushed to `dev` directly, one commit each; CI runs on
  every push all the same.
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

### Phase 1 — Finance MVP
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
- [x] React + TypeScript app with an API client generated from the OpenAPI spec
      (committed in `api/openapi.json`)
- [x] Dashboard, transaction feed, envelopes, reports, imports, goals, settings
      (mockups: light and dark themes, Ukrainian and English)
- [x] Backend changes the web client needs:
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
- [x] Recurring payments: reminders matched with bank operations, manual linking, confirmation or automatic
      creation
- [x] Balance forecast
- [x] Savings goals: target amount and date, linked account or Monobank jar, monthly amount needed,
      on-track / behind status
- [x] 50/30/20 analytics based on the needs / wants flag of categories
- [x] Analytics endpoints for the reports:
  - [x] plan vs actual per envelope with an end-of-month forecast from the current pace
  - [x] period comparison: cumulative spending by day for two periods (month to month, year to year,
        custom), optionally up to the same day, and the change per category
  - [x] spending trends: monthly amounts per category over a range, averages and anomalies
  - [x] savings: net saved and cumulative savings per month, savings rate against a target

### Phase 4 — Investments
- [x] Instruments, positions, trades, dividends and coupons, corporate actions (splits); lots with FIFO and the
      average price; value history with a benchmark
- [x] Market data providers chosen by configuration (Twelve Data daily closes, Alpha Vantage dividends)
- [x] Portfolio and value history in the base currency or another one (₴ / $ in the web client)
- [x] Freedom Finance import (trades and cash flows XLSX) with a preview; deposits, withdrawals and currency
      exchanges as operations
- [ ] Imports: Interactive Brokers, Ukrainian brokers, Binance, WhiteBIT, BTC / ETH addresses
- [x] Analytics: dividend calendar (received, expected, declared, forecast) with the yield, benchmark (S&P 500,
      MSCI World, Nasdaq 100), allocation by asset class and currency
- [x] Returns: time-weighted (TWR) and money-weighted (XIRR) over a period, also of the benchmark; allocation by
      sector (GICS)
- [ ] Analytics: allocation by country, rebalancing to target weights
- [x] Financial independence: FIRE number (lean / regular / fat / coast) and years to reach it,
      FYM (months of expenses covered by liquid money), compound interest calculator — computed in the web
      client from the account balances and the reports

### Phase 5 — Ukrainian taxes
- [x] Tax rates per year with defaults and links to the sources, all replaceable by the user
- [x] Sole proprietor (ФОП): account type, income from its accounts, chosen categories and chosen transactions;
      single tax, military levy and social contribution by quarter with deadlines and the income limit
- [x] Dividend tax with foreign withholding credit (when the document exists)
- [x] Investment profit tax using NBU rates on trade dates, with the loss carried over
- [x] Annual declaration summary
- [x] Declaration files (XML for the tax service cabinet): the sole proprietor of group 3 with the social
      contribution, the yearly declaration with Ф1

### Phase 6 — Notifications and mobile
- [x] Telegram bot: quick entry of expenses, incomes and transfers, commands, reminders, budget and tax alerts,
  weekly and monthly spending reports with charts
- [x] Email digest: the weekly and the monthly report by email, on by default, with one-click unsubscribe
- [ ] Push notifications (FCM / APNs)
- [ ] Native Android (Kotlin) and iOS (Swift) apps with PIN / biometrics

### Phase 7 — Public release readiness
- [x] TOTP two-factor authentication with recovery codes and trusted browsers (90 days)
- [x] Sign-in with Google (OpenID Connect with PKCE; a verified email links an existing account)
- [ ] Sign-in with Apple — together with the iOS app, it needs the Apple Developer Program
- [ ] CAPTCHA (Cloudflare Turnstile) on sign-in after a few failed passwords, also on registration and password reset;
  then the account-wide login block can go, so that wrong passwords from elsewhere cannot lock the owner out
- [x] Rate limiting of the whole API per client address, encryption of bank tokens and 2FA secrets at rest
- [x] Export of all data (ZIP: JSON of every owned table and transactions as CSV) and account deletion after a
  14-day grace period with a cancel link
- [x] Database backups; emails about logged errors (at most one per 15 minutes)
- [x] Privacy policy and terms of service in the web client

## License

All rights reserved. The source code is published for reference only: running, copying, modifying or distributing it
requires written permission from the author. See [LICENSE.md](LICENSE.md); versions before 20 October 2026 were
available under the PolyForm Noncommercial License 1.0.0.
