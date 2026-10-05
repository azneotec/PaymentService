# PaymentService

A small Spring Boot microservice that creates payment links through [Razorpay](https://razorpay.com/). Send it an order's amount and customer details, and it returns a Razorpay short URL the customer can use to pay.

## Tech stack

- Java 17
- Spring Boot 3.3.3 (Web, Actuator, DevTools)
- Razorpay Java SDK 1.4.10
- Lombok
- Maven (wrapper included)

## Project structure

```
src/main/java/com/azneotech/paymentservice
├── PaymentServiceApplication.java        # Spring Boot entry point
├── configs/RazorpayConfig.java           # Builds the RazorpayClient bean from properties
├── controllers/PaymentController.java    # POST /payment
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

`application.properties` maps them from the `RAZORPAY_KEY_ID` and `RAZORPAY_KEY_SECRET` variables. For local development, copy the example file and fill in your keys:

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

### Health check

Spring Boot Actuator is enabled: `GET /actuator/health`.

## Known limitations

These are hardcoded in `RazorpayPaymentGateway` and should be changed before real use:

- Currency is fixed to `INR`.
- Link expiry (`expire_by`) is a fixed timestamp (`1791590400`, early October 2026), so links created after that date will be rejected by Razorpay.
- The description (`Payment for policy no #23456`), the `policy_name` note and the `callback_url` are placeholder values.
- The customer's `name` and `contact` fields are swapped: the phone number is sent as the name, and the name as the contact.
- There are no request validation, webhook handling, or payment status endpoints yet.
