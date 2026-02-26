# Mock E-Commerce with PayPal Sandbox (React + Spring Boot)

This project is a **mock e-commerce application** with:

- **Frontend**: React (Vite) single-page app
- **Backend**: Spring Boot REST API with H2 in-memory DB
- **Payments**: PayPal **Sandbox** using **REST APIs** (Orders v2: create + capture)

The main goal is to demonstrate an **end-to-end flow** from product selection to PayPal checkout, capture, and **verifiable recording of successful purchases and their prices**.

---

## 1. Project Structure

- `backend/` – Spring Boot application
  - Exposes product, PayPal create-order, and capture-order APIs
  - Persists orders (including amount and status) in H2
- `frontend/` – React app
  - Displays mock products
  - Redirects to PayPal for approval
  - Handles PayPal return URL and calls backend to capture the order

---

## 2. Prerequisites

- Java 17+
- Maven 3.9+
- Node.js 18+ and npm
- A **PayPal Sandbox** account
  - A **REST app** (client id + secret)
  - One or more **sandbox buyer accounts** to test checkout

---

## 3. Backend Setup (Spring Boot)

### 3.1 Install dependencies

From the project root:

```bash
cd backend
mvn clean install
```

### 3.2 Configure PayPal sandbox credentials

**Do not commit real credentials.** Set these **environment variables** before running the backend:

```bash
export PAYPAL_CLIENT_ID=your_sandbox_client_id
export PAYPAL_CLIENT_SECRET=your_sandbox_client_secret
```

Or create a `.env` file in `backend/` (already gitignored) and source it. The app reads `PAYPAL_CLIENT_ID` and `PAYPAL_CLIENT_SECRET` from the environment.

Optionally, configure custom return/cancel URLs in `application.yml`:

```yaml
  return-url: http://localhost:5173/checkout/success
  cancel-url: http://localhost:5173/checkout/cancel
```

> These must match what you use in the frontend (and optionally in your PayPal app settings).

### 3.3 Run the backend

```bash
cd backend
mvn spring-boot:run
```

The backend will start on `http://localhost:8080`.

Key endpoints:

- `GET /api/products` – Returns an in-memory list of products
- `POST /api/paypal/create-order` – Creates a PayPal order for a chosen product
- `POST /api/paypal/capture-order?orderId={paypalOrderId}` – Captures the PayPal order and updates local order with status and amount

H2 console (optional, to inspect orders):

- `http://localhost:8080/h2-console` (driver `org.h2.Driver`, JDBC URL `jdbc:h2:mem:ecommercedb`, user `sa`)

---

## 4. Frontend Setup (React + Vite)

### 4.1 Install dependencies

From the project root:

```bash
cd frontend
npm install
```

### 4.2 Run the dev server

```bash
npm run dev
```

By default, Vite runs on `http://localhost:5173`.

The frontend expects the backend at `http://localhost:8080` (see `frontend/src/api.js`).

---

## 5. End-to-End User Flow (High Level)

1. **Browse products** in the React app (`/`).
2. Click **“Pay with PayPal”** on a product.
3. Frontend calls backend `POST /api/paypal/create-order` with `productId`.
4. Backend:
   - Looks up the product and its price.
   - Calls PayPal REST `POST /v2/checkout/orders` (intent = `CAPTURE`).
   - Persists a local `Order` with:
     - `externalOrderId` = PayPal order ID
     - `productName`
     - `amount` and `currencyCode`
     - `status = PENDING`
   - Returns `{ paypalOrderId, approvalUrl }` to frontend.
5. Frontend **redirects browser to `approvalUrl`** (PayPal).
6. User logs in with a **sandbox buyer account**, approves payment.
7. PayPal redirects back to `http://localhost:5173/checkout/success?token={paypalOrderId}` or to `/checkout/cancel` if cancelled.
8. On `/checkout/success`, the React app:
   - Reads `token` query param (the PayPal order ID).
   - Calls backend `POST /api/paypal/capture-order?orderId={token}`.
9. Backend:
   - Calls PayPal REST `POST /v2/checkout/orders/{id}/capture`.
   - Reads final `status` and the **captured amount & currency** from the PayPal response.
   - Looks up the matching `Order` in H2 by `externalOrderId`.
   - Compares the **captured amount** with the locally stored **expected amount**.
   - Updates `Order.status` (`COMPLETED` or `FAILED`) and `updatedAt`.
   - Returns a JSON summary to the frontend with `status`, `productName`, `amount`, and `currencyCode`.
10. Frontend displays a **confirmation page** showing:
    - Product name
    - Paid amount and currency
    - Status reported by PayPal

This ensures you can confirm **whether the product has been purchased successfully** and **at what price**.

---

## 6. Verifying Successful Purchase and Price

- On **frontend success page** (`/checkout/success`):
  - The app shows:
    - `productName`
    - `amount` and `currencyCode`
    - `status` from the backend (PayPal capture)
- On **backend / DB side**:
  - Check table `ORDERS` in H2:
    - `STATUS` should be `COMPLETED` for successful payments.
    - `AMOUNT` and `CURRENCY_CODE` store exactly what you charged.

The backend also compares the PayPal-captured amount with the expected order amount and logs a warning if they differ.

---

## 7. Quick Test Walkthrough

1. Start backend (`mvn spring-boot:run`).
2. Start frontend (`npm run dev`).
3. Open `http://localhost:5173`.
4. Choose a product (e.g., “Mock T-Shirt” for `$19.99`) and click **“Pay with PayPal”**.
5. Log in with your **sandbox buyer** and approve the payment.
6. You should be redirected to `/checkout/success` and see:
   - Product name
   - Paid amount and currency (e.g. `19.99 USD`)
   - Status (e.g. `COMPLETED`)
7. Optionally, open H2 console to see the `ORDERS` entry and verify the stored amount and status.

---

## 8. Developer Journey Document

For a deeper, step-by-step technical explanation of the integration and internal flows, see:

- `DEVELOPER_E2E_JOURNEY.md`

