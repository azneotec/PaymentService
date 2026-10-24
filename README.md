# PaymentService

A small Spring Boot microservice that creates payment links through [Stripe](https://stripe.com/) (the default; [Razorpay](https://razorpay.com/) is the alternative). Send it an order's amount and customer details, and it returns a short URL the customer can use to pay. It also takes payment requests from OrderManagementService over Kafka and reports whether each order was paid.

The link opens the gateway's hosted payment page, which shows the payment methods enabled for your account in the Stripe Dashboard (cards, wallets and others, depending on account and currency; Razorpay's offers cards, netbanking, UPI and wallets), so the customer picks the method there and card details never touch this service. Every payment and every gateway event is kept in a MySQL transaction log, and a paid order gets a receipt, by email and at `GET /payment/{orderNumber}/receipt`.

## Tech stack

- Java 17
- Spring Boot 3.3.3 (Web, Actuator, DevTools)
- Stripe Java SDK 32.2.0 and Razorpay Java SDK 1.4.10
- Spring Security: callers are authenticated against user-auth-service
- Spring Kafka
- Spring Data JPA, MySQL and Flyway (transaction log)
- Lombok
- Maven (wrapper included)

## Project structure

```
src/main/java/com/azneotech/paymentservice
├── PaymentServiceApplication.java        # Spring Boot entry point
├── configs/                              # RazorpayConfig, SecurityConfig, RestTemplateConfig
├── clients/AuthServiceClient.java        # Validates a bearer token against user-auth-service
├── security/                             # JwtAuthenticationFilter and the 401/403 handlers
├── controllers/PaymentController.java    # POST /payment
├── controllers/RazorpayWebhookController.java  # POST /razorpayWebhook (signed Razorpay events)
├── messaging/                            # Kafka: PaymentRequestConsumer, PaymentResultPublisher, ReceiptEmailPublisher and their messages
├── dtos/                                 # Request body, ReceiptDto
├── models/, repos/                       # Payment and PaymentEvent (the transaction log), Spring Data repositories
├── services/PaymentService.java          # Creates the link through the gateway and logs it
├── services/PaymentLedger.java           # Logs gateway outcomes and publishes them on payment-result
├── services/ReceiptService.java          # Builds the receipt of a paid order
├── paymentgateways/                      # IPaymentGateway; Stripe (default) and Razorpay implementations
└── resources/migration/                  # Flyway: V1__create_payments.sql
```

The service depends on the `IPaymentGateway` interface, so another provider can be added by implementing it.

## Prerequisites

- JDK 17+
- MySQL (`make infra-up` starts one, with Kafka, from `docker-compose.yml`)
- A Stripe account and API key (the [test-mode keys](https://dashboard.stripe.com/test/apikeys) work for development)
- For authenticated requests: Eureka and UserAuthService running (`make up` at the repository root)

## Configuration

The gateway credentials and the database are read from these properties:

| Property              | Description         |
|-----------------------|---------------------|
| `stripe.apiKey` | Stripe secret key (`STRIPE_API_KEY`); required, the service refuses to start without it |
| `stripe.webhook.secret` | Secret that signs Stripe webhooks (`STRIPE_WEBHOOK_SECRET`); empty rejects every Stripe webhook |
| `razorpay.key.id`, `razorpay.key.secret`, `razorpay.webhook.secret` | Optional: only when Razorpay is the gateway (`RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET`, `RAZORPAY_WEBHOOK_SECRET`) |
| `spring.datasource.*` | MySQL transaction log; `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`. Defaults to the compose MySQL on port 3307, database `payment_service` (created by `createDatabaseIfNotExist`; Flyway creates the tables) |
| `eureka.client.enabled` | `EUREKA_CLIENT_ENABLED`, default `true`. Token validation finds user-auth-service through Eureka, so with it `false` every authenticated request answers 503 |
| `server.port` | `SERVER_PORT`, default 8005 |

For local development, copy the example file and fill in your keys:

```bash
cp .env.example .env
```

The app loads `.env` from the working directory on startup (via `spring.config.import`). `.env` is listed in `.gitignore`, so it won't be committed. In deployed environments, set the two variables as real environment variables instead; they take precedence over `.env`.

## Running

```bash
make up        # MySQL + Kafka from docker-compose.yml, then the app on :8005 (make down stops them)
make run       # just the app, with .env loaded (MySQL and Kafka already running)
./mvnw spring-boot:run
```

`make help` lists the other targets (`jar`, `test`, `test-integration`, `infra-up`, `infra-down`, `infra-reset`). The root Makefile starts the whole system instead: `make payment` from the repository root.

The service starts on `http://localhost:8005`.

To build a jar and run it:

```bash
./mvnw clean package
java -jar target/PaymentService-0.0.1-SNAPSHOT.jar
```

### Run in Docker

The `Dockerfile` builds and runs only this service; MySQL, Kafka and Eureka stay outside and are reached through environment variables. `localhost` inside the container is the container itself, so the addresses must be overridden (`host.docker.internal` reaches services published on the host):

```bash
docker build -t payment-service .
docker run --rm -p 8005:8005 -e STRIPE_API_KEY=sk_test_... [-e STRIPE_WEBHOOK_SECRET=whsec_...] \
  -e DB_URL='jdbc:mysql://host.docker.internal:3307/payment_service?createDatabaseIfNotExist=true' \
  -e SPRING_KAFKA_BOOTSTRAP_SERVERS=host.docker.internal:9092 \
  -e EUREKA_CLIENT_SERVICEURL_DEFAULTZONE=http://host.docker.internal:8761/eureka/ \
  payment-service
```

The image carries no `.env` (it is in `.dockerignore`), runs as a non-root user, and has a health check on `/actuator/health`. With `EUREKA_CLIENT_ENABLED=false` the container runs without a registry, but authenticated requests then answer 503.

Run the tests with `./mvnw test` (no database needed). `make test-integration` also boots the full context against MySQL on 3307, which checks the Flyway migration against the JPA mappings.

## API

### `POST /payment`

Creates a payment link with the default gateway and returns its short URL as plain text. Needs `ROLE_ADMIN` (see [Authentication](#authentication)); the customer flow goes through Kafka.

**Request body**

| Field         | Type   | Description                                                    |
|---------------|--------|----------------------------------------------------------------|
| `amount`      | Long   | Amount in the smallest currency unit (paise for INR, so `100` = ₹1) |
| `phoneNumber` | String | Customer's phone number                                        |
| `name`        | String | Customer's name                                                |
| `email`       | String | Customer's email address                                       |
| `orderId`     | String | Your order ID, stored on the link (Razorpay `reference_id`, Stripe `orderId` metadata) |

**Example**

```bash
curl -X POST http://localhost:8005/payment \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "amount": 100,
    "phoneNumber": "+919999999999",
    "name": "Jane Doe",
    "email": "jane@example.com",
    "orderId": "OID#0001"
  }'
```

**Response**

```
https://rzp.io/rzp/lWsFo4S
```

If the gateway rejects the request, the service throws a `RuntimeException` carrying its error message, which surfaces as an HTTP 500. `409` when the order is already paid.

## Kafka: paying for orders

OrderManagementService publishes a message on `payment-requested` for every online order (key: the order number):

```json
{"orderNumber": "ORD-20261024-WAUH6NJZ", "amountMinor": 24220, "currency": "INR", "customerName": "Alice", "email": "alice@example.com", "phoneNumber": "+15551234567"}
```

PaymentService creates the payment link exactly as `POST /payment` does (`amountMinor` is the `amount`, the order number is the `orderId`, so Razorpay's `reference_id` or Stripe's `orderId` metadata) and answers on `payment-result`, keyed by the order number:

```json
{"orderNumber": "ORD-20261024-WAUH6NJZ", "status": "LINK_CREATED", "paymentLink": "https://rzp.io/rzp/lWsFo4S"}
{"orderNumber": "ORD-20261024-WAUH6NJZ", "status": "PAID"}
{"orderNumber": "ORD-20261024-WAUH6NJZ", "status": "FAILED", "message": "Payment link expired"}
```

`LINK_CREATED` is sent when the link exists; if the gateway refuses to create it, `FAILED` is sent straight away so the order does not wait for a link that will never come. `PAID` and `FAILED` after that come from the gateway's webhook. A request that is not valid JSON, or has no order number or a non-positive amount, is logged and dropped. Topic names are `payment.topics.*` in `application.properties`; the topics are created by the root `docker-compose.yml`.

### `GET /payment/{orderNumber}/receipt`

The receipt of a paid order:

```json
{"receiptNumber": "RCT-ORD-20261024-WAUH6NJZ", "orderNumber": "ORD-20261024-WAUH6NJZ", "gateway": "RAZORPAY", "paymentMethod": "upi",
 "gatewayPaymentId": "pay_Nk3...", "amount": "242.20", "amountMinor": 24220, "currency": "INR", "customerName": "Alice",
 "customerEmail": "a****@example.com", "paidAt": "2026-10-24T05:25:47.069106Z"}
```

Needs a token (see [Authentication](#authentication)). A customer gets only their own receipts, matched on the payment's customer email (the order's email is its owner's); an admin gets any. `404` when there is no payment for the order or it is someone else's (so order numbers cannot be probed), `409` while it is not paid. The email address is masked in the response; the full receipt goes to the customer by email. When the payment is confirmed the full receipt is also emailed to the customer: this service publishes it on `order-notification` (`payment.topics.receipt-notification`) in the `{to, from, subject, body}` shape NotificationService already sends. If that email cannot be queued the payment is still confirmed and the endpoint still works.

### `POST /stripeWebhook`

Receives Stripe's checkout events; see [Gateways](#gateways). Verified by the `Stripe-Signature` header, so it needs no token.

### `POST /razorpayWebhook`

Receives Razorpay's [payment link webhooks](https://razorpay.com/docs/webhooks/). In the Razorpay Dashboard create a webhook for `payment_link.paid`, `payment_link.expired` and `payment_link.cancelled`, pointing at a public URL for this endpoint (a tunnel such as ngrok for local work), and put its secret in `RAZORPAY_WEBHOOK_SECRET`.

| Event | Published on `payment-result` |
|---|---|
| `payment_link.paid` | `PAID` |
| `payment_link.expired`, `payment_link.cancelled` | `FAILED` |
| anything else | nothing (answered 200 so Razorpay does not retry) |

The order is found from the link's `reference_id`. The `X-Razorpay-Signature` header is verified against the raw body first: a missing or wrong signature gets 400, and with no secret configured every call gets 503. This endpoint decides whether an order counts as paid, so keep the secret out of source control.

### Authentication

`POST /payment` and `GET /payment/{orderNumber}/receipt` take `Authorization: Bearer <jwt>` and ask `user-auth-service` (found in Eureka, `POST /auth/validateToken`) whether it is valid, like OrderManagementService does. No or an invalid token gets `401`, a valid one without the role `403`, and `503` if user-auth-service cannot be reached.

| Endpoint | Who |
|---|---|
| `GET /payment/{orderNumber}/receipt` | `ROLE_USER` (own receipts) or `ROLE_ADMIN` |
| `POST /payment` | `ROLE_ADMIN` |
| `POST /stripeWebhook`, `POST /razorpayWebhook`, `GET /actuator/health` | open: webhooks are verified by the gateway's signature |

The Kafka flow carries no token and is not affected.

### Health check

Spring Boot Actuator is enabled: `GET /actuator/health`.

## Transaction log

Two MySQL tables, created by Flyway (`src/main/resources/migration`):

- `payments`: one row per order (unique `order_number`): gateway, amount in the smallest unit, currency, status (`LINK_CREATED`, `PAID`, `FAILED`), payment link, customer details, the gateway's payment id and method once known, receipt number and `paid_at`.
- `payment_event`: every event received for a payment: the link being created and each webhook, including repeats.

A paid payment never changes again, and a webhook repeated by the gateway is logged but not published twice. The row and the `payment-result` message are written in one transaction, so if Kafka is down the webhook answers 500 and the gateway's retry starts clean. A new request for an order that is already paid gets `409` from `POST /payment`, and `PAID` again on `payment-result` from Kafka.

## Gateways

`StripePaymentGateway` is `@Primary`, so `POST /payment` and the Kafka flow create Stripe links. `RazorpayPaymentGateway` is a second `IPaymentGateway` bean; to switch, move `@Primary` to it (and set the Razorpay keys). Both receive the same call (amount in the smallest currency unit, customer details, order number) and both put the order number on the link so their webhook can find the order.

| | Stripe (default) | Razorpay |
|---|---|---|
| Currency | `stripe.currency` (`inr`) | `razorpay.currency` (`INR`) |
| Link | one-time price named `Order <number>`, order number in the link's `orderId` metadata | `reference_id` = order number, full amount only, expires after `razorpay.link.expiry` (`24h`, Razorpay needs at least 15 minutes) |
| After paying | `stripe.redirect-url` (`STRIPE_REDIRECT_URL`), Stripe's own page when empty | `razorpay.callback-url` (`RAZORPAY_CALLBACK_URL`), omitted when empty |
| Webhook | `POST /stripeWebhook`, `STRIPE_WEBHOOK_SECRET` | `POST /razorpayWebhook`, `RAZORPAY_WEBHOOK_SECRET` |

The Stripe webhook verifies the `Stripe-Signature` header and reports `checkout.session.completed` (when `payment_status` is `paid`) and `checkout.session.async_payment_succeeded` as `PAID`, and `checkout.session.expired` and `checkout.session.async_payment_failed` as `FAILED`. Create the Stripe webhook for those four events, pointing at a public URL for `/stripeWebhook` (a tunnel such as ngrok, or `stripe listen`, for local work).

## Known limitations

- The currency comes from configuration, not from the `currency` field of a `payment-requested` message.
- There is no request validation on `POST /payment` and no payment status endpoint.
- Receipt ownership is matched on email, not user id (`payment-requested` carries no user id): a customer whose account email changed after ordering no longer matches their old receipts, though they still get the receipt email.
- Only Razorpay reports the payment method; a Stripe receipt has none (a Checkout Session does not carry it).
- If the MySQL transaction log is unavailable, a `payment-requested` message is redelivered like a Kafka failure (below) instead of failing the order.
- If Kafka is down when a `payment-result` is published, the webhook answers 500 so the gateway retries it; the `payment-requested` listener is retried a few times and then the message is dropped (and a retry can create a second payment link for the order).
