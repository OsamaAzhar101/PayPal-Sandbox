# Developer E2E Journey – Mock E-Commerce with PayPal REST

This document explains the **end-to-end technical flow** of the mock e-commerce application, focusing on how the **React frontend**, **Spring Boot backend**, and **PayPal Sandbox REST APIs** work together.

The key requirement is to know **if a product has been purchased successfully** and **at what price**, with a clear, traceable flow.

---

## 1. High-Level Architecture

- **Frontend (React, Vite)**
  - Routes:
    - `/` – Product listing and “Pay with PayPal” CTA
    - `/checkout/success` – PayPal return URL on successful approval
    - `/checkout/cancel` – PayPal cancel URL
  - Talks to backend via REST over HTTP.

- **Backend (Spring Boot)**
  - Exposes REST APIs:
    - `GET /api/products`
    - `POST /api/paypal/create-order`
    - `POST /api/paypal/capture-order`
  - Integrates with **PayPal REST APIs**:
    - `POST /v1/oauth2/token` – OAuth client credentials
    - `POST /v2/checkout/orders` – Create order
    - `POST /v2/checkout/orders/{id}/capture` – Capture order
  - Persists orders and amounts in **H2**.

---

## 2. Domain Model (Backend)

### 2.1 Product (in-memory)

Location: `backend/src/main/java/com/example/ecommerce/product`

- `Product` – simple POJO:
  - `id: Long`
  - `name: String`
  - `price: BigDecimal`
- `ProductService` – provides an in-memory list of mock products:
  - E.g. “Mock T-Shirt – 19.99”, “Mock Hoodie – 39.99”, etc.
- `ProductController`
  - `GET /api/products` → returns list of products to the frontend.

### 2.2 Order (persistent)

Location: `backend/src/main/java/com/example/ecommerce/order`

- `Order` (JPA entity, table `orders`):
  - `id: Long` – internal primary key
  - `externalOrderId: String` – the **PayPal order ID**
  - `productName: String`
  - `amount: BigDecimal` – the **expected charge amount** at the time of creation
  - `currencyCode: String` – e.g. `USD`
  - `status: OrderStatus` – `PENDING`, `APPROVED`, `COMPLETED`, `FAILED`
  - `createdAt: OffsetDateTime`
  - `updatedAt: OffsetDateTime`
- `OrderStatus` – enum for lifecycle statuses.
- `OrderRepository` – `JpaRepository<Order, Long>` with:
  - `findByExternalOrderId(String externalOrderId)`

This mapping ensures that for every PayPal order, we have a local record keyed by `externalOrderId` and containing the **amount** we expect the customer to pay.

---

## 3. PayPal Integration (Backend)

### 3.1 Configuration

Location: `backend/src/main/resources/application.yml`

Key properties:

- `paypal.base-url` – `https://api-m.sandbox.paypal.com`
- `paypal.client-id` – your sandbox REST app client id
- `paypal.client-secret` – your sandbox REST app client secret
- `paypal.currency-code` – e.g. `USD`
- `paypal.return-url` – frontend success URL, e.g. `http://localhost:5173/checkout/success`
- `paypal.cancel-url` – frontend cancel URL, e.g. `http://localhost:5173/checkout/cancel`

`PaypalConfig` defines a `WebClient` bean pointing to `paypal.base-url`.

### 3.2 OAuth – getting an access token

Class: `PaypalService`
Method: `obtainAccessToken()`

Flow:

1. Build HTTP Basic auth header:
   - `base64(clientId:clientSecret)`
2. Call:
   - `POST /v1/oauth2/token`
   - Headers:
     - `Authorization: Basic <base64(clientId:clientSecret)>`
     - `Content-Type: application/x-www-form-urlencoded`
   - Body: `grant_type=client_credentials`
3. Read `access_token` from JSON response.

This token is used for subsequent calls to create and capture orders.

### 3.3 Creating a PayPal order

Class: `PaypalService`
Method: `createOrderForProduct(Product product)`

Steps:

1. Call `obtainAccessToken()` to get a Bearer token.
2. Build payload for `POST /v2/checkout/orders`:

   - Intent: `CAPTURE`
   - Purchase units:
     - `amount.currency_code` = `paypal.currency-code` (e.g. `USD`)
     - `amount.value` = `product.price.toString()` (e.g. `19.99`)
     - `description` = `product.name`
   - Application context:
     - `return_url` = `paypal.return-url`
     - `cancel_url` = `paypal.cancel-url`

3. Send request:

   - `POST /v2/checkout/orders`
   - Header: `Authorization: Bearer <access_token>`
   - Body: JSON payload above.

4. Parse response:
   - `id` – the PayPal order ID (stored as `externalOrderId` in our `Order`).
   - `links` → find the link where `rel = "approve"` → this is the **approval URL**.

5. Create and persist a local `Order`:

   - `externalOrderId = paypalOrderId`
   - `productName = product.name`
   - `amount = product.price`
   - `currencyCode = paypal.currency-code`
   - `status = PENDING`
   - `createdAt` and `updatedAt` = now

6. Return `CreateOrderResponse` to caller:

   - `paypalOrderId`
   - `approvalUrl`

### 3.4 Capturing a PayPal order

Class: `PaypalService`
Method: `captureOrder(String paypalOrderId)`

Steps:

1. Call `obtainAccessToken()` to get Bearer token.
2. Call `POST /v2/checkout/orders/{orderId}/capture`:
   - URI: `/v2/checkout/orders/{orderId}/capture`
   - Header: `Authorization: Bearer <access_token>`
   - Body: empty JSON or omitted (current implementation sends no special fields).
3. Parse response:
   - Top-level `status` – expected `COMPLETED` for successful capture.
   - `purchase_units[0].payments.captures[0].amount`:
     - `value` – actual amount captured
     - `currency_code` – currency
4. Convert `value` to `BigDecimal` → `paidAmount`.
5. Load corresponding `Order` from DB:
   - `orderRepository.findByExternalOrderId(paypalOrderId)`.
6. Compare amounts:
   - If `paidAmount.compareTo(order.getAmount()) != 0`:
     - Log a **warning** for amount mismatch.
7. Update local order:
   - If PayPal `status` is `COMPLETED` → `OrderStatus.COMPLETED`
   - Otherwise → `OrderStatus.FAILED`
   - `updatedAt = now`
8. Persist order.
9. Return `CaptureOrderResponse`:
   - `status` – PayPal order status
   - `productName` – from local order
   - `amount` – `paidAmount`
   - `currencyCode` – parsed from PayPal response

This provides a **single source of truth** for success and exact paid amount.

---

## 4. Backend REST API Layer

### 4.1 Product API

Class: `ProductController`

- `GET /api/products`
  - Returns the static list from `ProductService`.
  - Used by the React app to show available products and prices.

### 4.2 PayPal API

Class: `PaypalController`

- `POST /api/paypal/create-order`

  - Request body: `CreateOrderRequest`:
    - `productId: Long`
  - Flow:
    1. Load `Product` by `productId` via `ProductService`.
    2. Call `PaypalService.createOrderForProduct(product)`.
    3. Return `CreateOrderResponse` (contains `paypalOrderId`, `approvalUrl`).

- `POST /api/paypal/capture-order`

  - Query param: `orderId` (the PayPal `token` received on success redirect).
  - Flow:
    1. Call `PaypalService.captureOrder(orderId)`.
    2. Return `CaptureOrderResponse` (status, productName, amount, currencyCode).

These endpoints are designed for a clean **server-mediated** PayPal integration: the frontend never handles PayPal client credentials or performs PayPal REST calls directly.

---

## 5. Frontend Flow (React)

### 5.1 API helper

File: `frontend/src/api.js`

- `fetchProducts()` → calls `GET http://localhost:8080/api/products`
- `createOrder(productId)` → calls `POST http://localhost:8080/api/paypal/create-order`
- `captureOrder(orderId)` → calls `POST http://localhost:8080/api/paypal/capture-order?orderId={...}`

### 5.2 Product list and starting the PayPal flow

Component: `ProductList` (`frontend/src/components/ProductList.jsx`)

Flow:

1. On mount:
   - Calls `fetchProducts()`.
   - Renders cards for each product (name, price).
2. On “Pay with PayPal” click:
   - Calls `createOrder(productId)` on the backend.
   - Receives `{ approvalUrl, paypalOrderId }`.
   - Sets `window.location.href = approvalUrl`.
   - Browser navigates to PayPal’s approval page.

### 5.3 Handling PayPal return URL and capturing payment

Component: `CheckoutSuccess` (`frontend/src/components/CheckoutSuccess.jsx`)

When PayPal completes user approval, it redirects to:

- `http://localhost:5173/checkout/success?token={paypalOrderId}&PayerID={...}`

Flow:

1. On mount:
   - Read `token` from query string using `useSearchParams`.
   - If missing → show error.
2. Call `captureOrder(orderId)` with `orderId = token`.
3. Once backend responds with `CaptureOrderResponse`:
   - Render:
     - **Product name**
     - **Paid amount + currency**
     - **Status from PayPal** (e.g. `COMPLETED`)

This is the key place where a developer or stakeholder can visually verify **successful purchase** and **exact price paid**.

### 5.4 Handling cancel URL

Component: `CheckoutCancel` (`frontend/src/components/CheckoutCancel.jsx`)

- Simple page showing that the user cancelled the payment.
- Linked to PayPal cancel URL: `http://localhost:5173/checkout/cancel`.

---

## 6. CORS & Local Development

Class: `CorsConfig` (`backend/src/main/java/com/example/ecommerce/config/CorsConfig.java`)

- Configures CORS for `/api/**`:
  - `allowedOrigins("http://localhost:5173")`
  - Methods: `GET`, `POST`, `PUT`, `DELETE`, `OPTIONS`

This allows the React dev server to call the Spring Boot backend in browser.

---

## 7. Sequence of Calls – Detailed E2E

### 7.1 Create order phase

User action: click “Pay with PayPal”

1. React:
   - `POST /api/paypal/create-order` with body `{ "productId": 1 }`
2. Spring Boot:
   - `ProductService.getById(1)` → get product with its price.
   - `PaypalService.createOrderForProduct(product)`:
     - `obtainAccessToken()` → PayPal `/v1/oauth2/token`.
     - `POST /v2/checkout/orders` with amount and description.
     - Parse `paypalOrderId` and `approvalUrl`.
     - Persist local `Order` with `status=PENDING`, `amount=product.price`.
   - Return `{ "paypalOrderId": "...", "approvalUrl": "https://www.sandbox.paypal.com/..." }`.
3. React:
   - Redirect to `approvalUrl`.

### 7.2 Approval & redirect

1. User approves payment on PayPal using sandbox buyer.
2. PayPal redirects to:
   - `http://localhost:5173/checkout/success?token={paypalOrderId}&PayerID=...`

### 7.3 Capture phase

1. React (`CheckoutSuccess`):
   - Reads `token` from URL.
   - Calls `POST /api/paypal/capture-order?orderId={token}`.
2. Spring Boot:
   - `PaypalService.captureOrder(orderId)`:
     - `obtainAccessToken()` again.
     - `POST /v2/checkout/orders/{orderId}/capture`.
     - Parse `status`, `purchase_units[0].payments.captures[0].amount`.
     - `paidAmount = new BigDecimal(value)`.
     - `currency = currency_code`.
     - Load `Order` from DB by `externalOrderId = orderId`.
     - Compare `paidAmount` vs `order.getAmount()` and log if mismatch.
     - Set `Order.status` to `COMPLETED` or `FAILED`.
     - Save `Order`.
     - Return `CaptureOrderResponse`.
3. React:
   - Renders “Payment Successful” with:
     - Product name (from backend)
     - Paid amount and currency
     - Status

Result: You now have **visual confirmation** plus **database records** for each successful purchase.

---

## 8. How to Extend This Implementation

- **Multiple items / carts**
  - Instead of a single `Product`, create a list of cart items and sum their amounts.
  - Map each cart line to a `purchase_unit` item or line item inside a single purchase unit.
- **Real products & persistence**
  - Replace `ProductService` in-memory list with a JPA entity and repository.
- **Environment management**
  - Externalize PayPal credentials (`PAYPAL_CLIENT_ID`, `PAYPAL_CLIENT_SECRET`) via environment variables and map them into `application.yml` using `${...}` syntax.
- **Production vs sandbox**
  - Use profiles (`application-sandbox.yml`, `application-prod.yml`) to switch base URLs and credentials.

---

## 9. What Developers Should Check to Confirm a Purchase

1. **Frontend**
   - On `/checkout/success`, ensure the UI shows:
     - Product name.
     - Amount and currency.
     - Status = `COMPLETED`.
2. **Backend logs**
   - Look for log lines from `PaypalService` that show created order IDs and any mismatch warnings.
3. **Database (H2)**
   - Inspect the `orders` table:
     - `externalOrderId` matches the PayPal order ID (`token`).
     - `status` is `COMPLETED`.
     - `amount` equals the expected product price.
     - `currency_code` is as configured (e.g. `USD`).

By following these steps, a developer can **fully trace the journey** of a purchase from the UI to PayPal and back, and confidently answer:

- **Was the product purchased successfully?**
- **Exactly what price was paid?**

