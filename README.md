# PaymentService

A small Spring Boot microservice that creates payment links through [Razorpay](https://razorpay.com/). Send it an order's amount and customer details, and it returns a Razorpay short URL the customer can use to pay. It also takes payment requests from OrderManagementService over Kafka and reports whether each order was paid.

## Tech stack

- Java 17
- Spring Boot 3.3.3 (Web, Actuator, DevTools)
- Razorpay Java SDK 1.4.10
- Spring Kafka
- Lombok
- Maven (wrapper included)

## Project structure

```
src/main/java/com/azneotech/paymentservice
├── PaymentServiceApplication.java        # Spring Boot entry point
├── configs/RazorpayConfig.java           # Builds the RazorpayClient bean from properties
├── controllers/PaymentController.java    # POST /payment
├── controllers/RazorpayWebhookController.java  # POST /razorpayWebhook (signed Razorpay events)
├── messaging/                            # Kafka: PaymentRequestConsumer, PaymentResultPublisher and their messages
├── dtos/InitiatePaymentRequestDto.java   # Request body
├── services/PaymentService.java          # Delegates to the configured gateway
└── paymentgateways/
    ├── IPaymentGateway.java              # Gateway abstraction
    └── RazorpayPaymentGateway.java       # Razorpay implementation
```

The service depends on the `IPaymentGateway` interface, so another provider can be added by implementing it.

## Prerequisites

- JDK 17+
- A Razorpay account and API keys (the [test-mode keys](https://dashboard.razorpay.com/app/keys) work for development)

## Configuration

The Razorpay credentials are read from these properties:

| Property              | Description         |
|-----------------------|---------------------|
| `razorpay.key.id`     | Razorpay key ID     |
| `razorpay.key.secret` | Razorpay key secret |
| `razorpay.webhook.secret` | Secret that signs Razorpay webhooks; empty rejects every webhook |
| `stripe.apiKey`, `stripe.webhook.secret` | Optional: only for Stripe (`STRIPE_API_KEY`, `STRIPE_WEBHOOK_SECRET`) |

`application.properties` maps them from the `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET` and `RAZORPAY_WEBHOOK_SECRET` variables (the Stripe ones are optional). For local development, copy the example file and fill in your keys:

```bash
cp .env.example .env
```

The app loads `.env` from the working directory on startup (via `spring.config.import`). `.env` is listed in `.gitignore`, so it won't be committed. In deployed environments, set the two variables as real environment variables instead; they take precedence over `.env`.

## Running

```bash
./mvnw spring-boot:run
```

The service starts on `http://localhost:8080`.

To build a jar and run it:

```bash
./mvnw clean package
java -jar target/PaymentService-0.0.1-SNAPSHOT.jar
```

Run the tests with `./mvnw test`.

## API

### `POST /payment`

Creates a Razorpay payment link and returns its short URL as plain text.

**Request body**

| Field         | Type   | Description                                                    |
|---------------|--------|----------------------------------------------------------------|
| `amount`      | Long   | Amount in the smallest currency unit (paise for INR, so `100` = ₹1) |
| `phoneNumber` | String | Customer's phone number                                        |
| `name`        | String | Customer's name                                                |
| `email`       | String | Customer's email address                                       |
| `orderId`     | String | Your order ID, sent to Razorpay as `reference_id`              |

**Example**

```bash
curl -X POST http://localhost:8080/payment \
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

If Razorpay rejects the request, the service throws a `RuntimeException` carrying the Razorpay error message, which surfaces as an HTTP 500.

## Kafka: paying for orders

OrderManagementService publishes a message on `payment-requested` for every online order (key: the order number):

```json
{"orderNumber": "ORD-20261024-WAUH6NJZ", "amountMinor": 24220, "currency": "INR", "customerName": "Alice", "email": "alice@example.com", "phoneNumber": "+15551234567"}
```

PaymentService creates the payment link exactly as `POST /payment` does (`amountMinor` is the `amount`, the order number is the `orderId`, so Razorpay's `reference_id`) and answers on `payment-result`, keyed by the order number:

```json
{"orderNumber": "ORD-20261024-WAUH6NJZ", "status": "LINK_CREATED", "paymentLink": "https://rzp.io/rzp/lWsFo4S"}
{"orderNumber": "ORD-20261024-WAUH6NJZ", "status": "PAID"}
{"orderNumber": "ORD-20261024-WAUH6NJZ", "status": "FAILED", "message": "Payment link expired"}
```

`LINK_CREATED` is sent when the link exists; if the gateway refuses to create it, `FAILED` is sent straight away so the order does not wait for a link that will never come. `PAID` and `FAILED` after that come from Razorpay's webhook. A request that is not valid JSON, or has no order number or a non-positive amount, is logged and dropped. Topic names are `payment.topics.*` in `application.properties`; the topics are created by the root `docker-compose.yml`.

### `POST /razorpayWebhook`

Receives Razorpay's [payment link webhooks](https://razorpay.com/docs/webhooks/). In the Razorpay Dashboard create a webhook for `payment_link.paid`, `payment_link.expired` and `payment_link.cancelled`, pointing at a public URL for this endpoint (a tunnel such as ngrok for local work), and put its secret in `RAZORPAY_WEBHOOK_SECRET`.

| Event | Published on `payment-result` |
|---|---|
| `payment_link.paid` | `PAID` |
| `payment_link.expired`, `payment_link.cancelled` | `FAILED` |
| anything else | nothing (answered 200 so Razorpay does not retry) |

The order is found from the link's `reference_id`. The `X-Razorpay-Signature` header is verified against the raw body first: a missing or wrong signature gets 400, and with no secret configured every call gets 503. This endpoint decides whether an order counts as paid, so keep the secret out of source control.

### Health check

Spring Boot Actuator is enabled: `GET /actuator/health`.

## Gateways

`RazorpayPaymentGateway` is `@Primary`, so `POST /payment` and the Kafka flow create Razorpay links. `StripePaymentGateway` is a second `IPaymentGateway` bean; to switch, move `@Primary` to it. Both receive the same call (amount in the smallest currency unit, customer details, order number) and both put the order number on the link so their webhook can find the order.

| | Razorpay (default) | Stripe |
|---|---|---|
| Currency | `razorpay.currency` (`INR`) | `stripe.currency` (`inr`) |
| Link | `reference_id` = order number, full amount only, expires after `razorpay.link.expiry` (`24h`, Razorpay needs at least 15 minutes) | one-time price named `Order <number>`, order number in the link's `orderId` metadata |
| After paying | `razorpay.callback-url` (`RAZORPAY_CALLBACK_URL`), omitted when empty | `stripe.redirect-url` (`STRIPE_REDIRECT_URL`), Stripe's own page when empty |
| Webhook | `POST /razorpayWebhook`, `RAZORPAY_WEBHOOK_SECRET` | `POST /stripeWebhook`, `STRIPE_WEBHOOK_SECRET` |

The Stripe webhook verifies the `Stripe-Signature` header and reports `checkout.session.completed` (when `payment_status` is `paid`) and `checkout.session.async_payment_succeeded` as `PAID`, and `checkout.session.expired` and `checkout.session.async_payment_failed` as `FAILED`. Create the Stripe webhook for those four events. `STRIPE_API_KEY` is only needed when Stripe is used; without it the service still starts.

## Known limitations

- The currency comes from configuration, not from the `currency` field of a `payment-requested` message.
- There is no request validation on `POST /payment` and no payment status endpoint.
- If Kafka is down when a `payment-result` is published, the webhook answers 500 so the gateway retries it; the `payment-requested` listener is retried a few times and then the message is dropped (and a retry can create a second payment link for the order).
