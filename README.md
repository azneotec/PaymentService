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

`application.properties` maps them from the `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET` and `RAZORPAY_WEBHOOK_SECRET` variables. For local development, copy the example file and fill in your keys:

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

## Known limitations

These are hardcoded in `RazorpayPaymentGateway` and should be changed before real use:

- Currency is fixed to `INR`, whatever `currency` a `payment-requested` message carries.
- `StripePaymentGateway` is `@Primary`, so payment links (from `POST /payment` and from Kafka) are currently created through Stripe, with a fixed monthly "Gold Plan" price in USD, while the webhook above only understands Razorpay events. Make `RazorpayPaymentGateway` the primary gateway before relying on `PAID` coming back.
- Link expiry (`expire_by`) is a fixed timestamp (`1791590400`, early October 2026), so links created after that date will be rejected by Razorpay.
- The description (`Payment for policy no #23456`), the `policy_name` note and the `callback_url` are placeholder values.
- The customer's `name` and `contact` fields are swapped: the phone number is sent as the name, and the name as the contact.
- There is no request validation on `POST /payment`, no Stripe webhook handling and no payment status endpoint.
- If Kafka is down when a `payment-result` is published, the webhook answers 500 so Razorpay retries it; the `payment-requested` listener is retried a few times and then the message is dropped (and a retry can create a second payment link for the order).
