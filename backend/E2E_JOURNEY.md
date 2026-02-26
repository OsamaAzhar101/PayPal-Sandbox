# PayPal E-Commerce Backend - End-to-End Journey

## 📋 Project Overview

This is a **Spring Boot-based e-commerce backend** that integrates with **PayPal REST API** to handle product browsing and payment processing. It demonstrates a complete payment workflow from product selection through payment capture.

### Tech Stack
- **Framework**: Spring Boot 3.2.5
- **Language**: Java 17
- **Database**: H2 (In-Memory)
- **ORM**: JPA/Hibernate
- **HTTP Client**: Spring WebClient (Reactive)
- **Build Tool**: Maven
- **Payment Gateway**: PayPal Sandbox API

---

## 🏗️ Architecture Overview

```
┌─────────────────────────────────────────────────────┐
│          Frontend (React/Vue - Port 5173)           │
└────────────────────────┬────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────┐
│       Backend API Server (Spring Boot - Port 8080)  │
├─────────────────────────────────────────────────────┤
│                                                     │
│  PaypalController    ProductController             │
│        │                    │                       │
│        ▼                    ▼                       │
│  PaypalService       ProductService                │
│        │                    │                       │
│        └────────┬───────────┘                       │
│                 ▼                                   │
│          OrderRepository                           │
│          ProductRepository                         │
│                 │                                   │
│                 ▼                                   │
│          H2 In-Memory Database                     │
│                                                     │
└────────────────────────┬────────────────────────────┘
                         │
                         ▼
        ┌────────────────────────────┐
        │  PayPal Sandbox API        │
        │  (api-m.sandbox.paypal.com)│
        │                            │
        │  • OAuth2 Authentication   │
        │  • Create Order            │
        │  • Capture Payment         │
        └────────────────────────────┘
```

---

## 📊 Data Models

### 1. **Product**
```java
- id: Long (Primary Key)
- name: String (e.g., "Laptop", "Smartphone")
- price: BigDecimal (e.g., 999.99)
```
**Note**: Products are in-memory (not persisted in database initially).

### 2. **Order**
```java
- id: Long (Auto-generated)
- externalOrderId: String (PayPal Order ID - unique)
- productName: String
- amount: BigDecimal (with 2 decimal places)
- currencyCode: String (e.g., "USD")
- status: OrderStatus (PENDING, COMPLETED, FAILED)
- createdAt: OffsetDateTime
- updatedAt: OffsetDateTime
```

### 3. **OrderStatus Enum**
```
- PENDING: Order created, awaiting payment
- COMPLETED: Payment captured successfully
- FAILED: Payment capture failed
```

---

## 🔄 Complete E2E Journey

### **Phase 1: User Browses Products**

#### Request
```http
GET http://localhost:8080/api/products
```

#### Response
```json
[
  {
    "id": 1,
    "name": "Laptop Pro",
    "price": 1299.99
  },
  {
    "id": 2,
    "name": "Wireless Mouse",
    "price": 29.99
  },
  {
    "id": 3,
    "name": "USB-C Cable",
    "price": 15.99
  }
]
```

#### What Happens Behind the Scenes
1. Frontend calls `/api/products` endpoint
2. **ProductController** receives the request
3. **ProductService** retrieves pre-loaded products from in-memory list
4. Products are returned to frontend for display

---

### **Phase 2: User Initiates Checkout (Create Order)**

#### Request
```http
POST http://localhost:8080/api/paypal/create-order
Content-Type: application/json

{
  "productId": 1
}
```

#### Response
```json
{
  "paypalOrderId": "7NA2AJ3NJC4JK",
  "approvalUrl": "https://sandbox.paypal.com/checkoutnow?token=7NA2AJ3NJC4JK"
}
```

#### What Happens Behind the Scenes

**Step 1: Product Validation**
```
ProductController validates that productId=1 exists
Retrieves Product(1, "Laptop Pro", 1299.99)
```

**Step 2: PayPal OAuth2 Authentication**
```
PaypalService.obtainAccessToken() is called
- Client ID + Client Secret are Base64 encoded
- POST to: https://api-m.sandbox.paypal.com/v1/oauth2/token
- Headers: Authorization: Basic <base64(clientId:clientSecret)>
- Body: grant_type=client_credentials
- Response contains access_token (valid for ~1 hour)
```

**Step 3: Create PayPal Order**
```
PaypalService.createOrderForProduct() constructs payload:
{
  "intent": "CAPTURE",
  "purchase_units": [
    {
      "amount": {
        "currency_code": "USD",
        "value": "1299.99"
      },
      "description": "Laptop Pro"
    }
  ],
  "application_context": {
    "return_url": "http://localhost:5173/checkout/success",
    "cancel_url": "http://localhost:5173/checkout/cancel"
  }
}

POST to: https://api-m.sandbox.paypal.com/v2/checkout/orders
Header: Authorization: Bearer <access_token>
```

**Step 4: PayPal Response Processing**
```
PayPal responds with:
{
  "id": "7NA2AJ3NJC4JK",
  "status": "CREATED",
  "links": [
    {
      "rel": "approve",
      "href": "https://sandbox.paypal.com/checkoutnow?token=7NA2AJ3NJC4JK"
    },
    ...
  ]
}
```

**Step 5: Local Order Creation**
```
Backend creates Order in database:
- externalOrderId: "7NA2AJ3NJC4JK" (from PayPal)
- productName: "Laptop Pro"
- amount: 1299.99
- currencyCode: "USD"
- status: PENDING
- createdAt: 2026-02-25T10:30:00+00:00
- updatedAt: 2026-02-25T10:30:00+00:00
```

**Step 6: Response to Frontend**
```
Return approvalUrl to frontend so user can redirect to PayPal
```

---

### **Phase 3: User Authorizes Payment on PayPal**

#### What Happens
1. Frontend redirects user to PayPal approval URL
2. User logs into PayPal sandbox account
3. User reviews the order details (Laptop Pro - $1299.99)
4. User clicks "Approve and Continue"
5. PayPal redirects back to frontend with `token=7NA2AJ3NJC4JK` in URL

#### Frontend Flow (Not in Backend)
```
Approval URL: https://sandbox.paypal.com/checkoutnow?token=7NA2AJ3NJC4JK
    ↓
User approves payment
    ↓
PayPal redirects to: http://localhost:5173/checkout/success?token=7NA2AJ3NJC4JK
    ↓
Frontend captures token and calls capture-order endpoint
```

---

### **Phase 4: Backend Captures Payment**

#### Request
```http
POST http://localhost:8080/api/paypal/capture-order?orderId=7NA2AJ3NJC4JK
```

#### Response (Success Case)
```json
{
  "status": "COMPLETED",
  "productName": "Laptop Pro",
  "amount": 1299.99,
  "currencyCode": "USD"
}
```

#### What Happens Behind the Scenes

**Step 1: Input Validation**
```
Validate that orderId is not null/blank
orderId: "7NA2AJ3NJC4JK" ✓
```

**Step 2: Obtain Fresh Access Token**
```
Call obtainAccessToken() again to get a valid token
(This ensures we have an active token for the capture request)
```

**Step 3: Call PayPal Capture API**
```
POST to: https://api-m.sandbox.paypal.com/v2/checkout/orders/7NA2AJ3NJC4JK/capture
Headers:
  - Content-Type: application/json
  - Authorization: Bearer <new_access_token>
Body: {} (empty, PayPal knows the order from URL)
```

**Step 4A: Success Flow - PayPal Confirms Payment**
```
PayPal responds:
{
  "id": "7NA2AJ3NJC4JK",
  "status": "COMPLETED",
  "purchase_units": [
    {
      "description": "Laptop Pro",
      "payments": {
        "captures": [
          {
            "amount": {
              "value": "1299.99",
              "currency_code": "USD"
            }
          }
        ]
      }
    }
  ]
}
```

**Step 5A: Update Order Status in Database**
```
Find order by externalOrderId: "7NA2AJ3NJC4JK"
Update:
  - status: COMPLETED
  - updatedAt: 2026-02-25T10:35:00+00:00
Save to database
```

**Step 6A: Return Confirmation**
```
Response to frontend:
{
  "status": "COMPLETED",
  "productName": "Laptop Pro",
  "amount": 1299.99,
  "currencyCode": "USD"
}
```

---

### **Phase 4B: Duplicate Capture (Error Handling)**

#### Scenario: User clicks capture twice (network retry)

#### Request
```http
POST http://localhost:8080/api/paypal/capture-order?orderId=7NA2AJ3NJC4JK
```

#### What Happens Behind the Scenes
```
PayPal API responds with:
Status: 422 (Unprocessable Entity)
Error: "ORDER_ALREADY_CAPTURED"

Backend detects this is not a fatal error:
1. Catches WebClientResponseException
2. Checks if status is 422 AND body contains "ORDER_ALREADY_CAPTURED"
3. Instead of failing, it calls fetchOrderDetails() to get current state
4. Extracts payment info from order details
5. Builds response as if payment was successful
6. Updates local order status to COMPLETED
```

#### Response (Graceful Handling)
```json
{
  "status": "COMPLETED",
  "productName": "Laptop Pro",
  "amount": 1299.99,
  "currencyCode": "USD"
}
```

**This prevents duplicate charge and provides good UX!**

---

### **Phase 4C: Capture Fails (Error Handling)**

#### Scenario: Insufficient funds, card declined, etc.

#### Request
```http
POST http://localhost:8080/api/paypal/capture-order?orderId=7NA2AJ3NJC4JK
```

#### What Happens Behind the Scenes
```
PayPal API responds with error:
Status: 400 (Bad Request)
Body: {
  "name": "INSTRUMENT_DECLINED",
  "message": "The instrument presented was either declined by the processor...",
  "details": [
    {
      "description": "Card declined by issuer"
    }
  ]
}

Backend error handling:
1. Catches WebClientResponseException
2. Checks it's NOT the ORDER_ALREADY_CAPTURED case
3. Marks order as FAILED in database:
   - status: FAILED
   - updatedAt: 2026-02-25T10:35:30+00:00
4. Extracts error message from PayPal response
5. Throws IllegalStateException with error details
```

#### Response (Error)
```json
HTTP/1.1 500 Internal Server Error
Content-Type: application/json

{
  "timestamp": "2026-02-25T10:35:30.123Z",
  "status": 500,
  "error": "Internal Server Error",
  "message": "Card declined by issuer",
  "path": "/api/paypal/capture-order"
}
```

#### Database State
```
Order with externalOrderId: "7NA2AJ3NJC4JK"
- status: FAILED (updated from PENDING)
- updatedAt: 2026-02-25T10:35:30+00:00
```

---

## 🔐 Security Features

### 1. **OAuth2 Client Credentials Flow**
- Uses Client ID and Client Secret (Base64 encoded)
- Each API request to PayPal includes Bearer token
- Access tokens have expiration (auto-refreshed per request)

### 2. **Input Validation**
- `@Valid` annotation on request DTOs
- Null/blank checks for critical fields
- Product ID validation before processing

### 3. **CORS Configuration**
- Allows cross-origin requests from frontend
- Configured in `CorsConfig.java`

### 4. **Error Handling**
- Global exception handler for consistent error responses
- Sensitive information not exposed to client
- Proper HTTP status codes (422 for business logic errors, 400 for bad requests)

---

## 🛠️ Configuration

### PayPal Credentials (environment variables)

Do **not** put real credentials in the repo. Set these before running the backend:

- `PAYPAL_CLIENT_ID` – your sandbox app client ID  
- `PAYPAL_CLIENT_SECRET` – your sandbox app client secret  

`application.yml` reads them:

```yaml
paypal:
  base-url: https://api-m.sandbox.paypal.com
  client-id: ${PAYPAL_CLIENT_ID:}
  client-secret: ${PAYPAL_CLIENT_SECRET:}
  currency-code: USD
  return-url: http://localhost:5173/checkout/success
  cancel-url: http://localhost:5173/checkout/cancel
```

### Database Configuration
```yaml
spring:
  datasource:
    url: jdbc:h2:mem:ecommercedb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE
    driverClassName: org.h2.Driver
    username: sa
    password: password
  jpa:
    hibernate:
      ddl-auto: update
```

---

## 📡 API Endpoints

### 1. **List Products**
```http
GET /api/products

Response: Array of Product objects
```

### 2. **Create Order**
```http
POST /api/paypal/create-order
Content-Type: application/json

Request:
{
  "productId": 1
}

Response:
{
  "paypalOrderId": "7NA2AJ3NJC4JK",
  "approvalUrl": "https://sandbox.paypal.com/checkoutnow?token=7NA2AJ3NJC4JK"
}
```

### 3. **Capture Order**
```http
POST /api/paypal/capture-order?orderId=7NA2AJ3NJC4JK

Response:
{
  "status": "COMPLETED",
  "productName": "Laptop Pro",
  "amount": 1299.99,
  "currencyCode": "USD"
}
```

---

## 📝 Complete Example Workflow

### Step-by-Step User Journey

```
1️⃣  USER BROWSES PRODUCTS
   Frontend: GET /api/products
   Backend: Returns list of products
   Display: Shows "Laptop Pro - $1299.99"

2️⃣  USER ADDS TO CART & PROCEEDS TO CHECKOUT
   (Frontend logic - not shown)

3️⃣  BACKEND CREATES PAYPAL ORDER
   Frontend: POST /api/paypal/create-order
   Payload: { "productId": 1 }
   Backend: 
     - Fetches product details
     - Calls PayPal OAuth2 to get access token
     - Calls PayPal create-order endpoint
     - Saves order to database (status: PENDING)
     - Returns PayPal approval URL
   Response: { "paypalOrderId": "ABC123", "approvalUrl": "..." }

4️⃣  USER REDIRECTED TO PAYPAL.COM
   Frontend: Redirects to approvalUrl
   User: Logs in to PayPal Sandbox
   User: Reviews order details
   User: Clicks "Approve and Continue"
   PayPal: Redirects back to frontend with token

5️⃣  FRONTEND CAPTURES ORDER
   Frontend: POST /api/paypal/capture-order?orderId=ABC123
   Backend:
     - Gets fresh access token
     - Calls PayPal capture endpoint
     - Receives payment confirmation
     - Updates order status to COMPLETED
     - Saves to database
   Response: { "status": "COMPLETED", ... }

6️⃣  PAYMENT SUCCESSFUL
   Frontend: Shows success message
   User: Receives confirmation email (from PayPal)
   Order: Stored in database with status COMPLETED
```

---

## 🚀 Running the Application

### Prerequisites
- Java 17+
- Maven 3.6+

### Build
```bash
mvn clean install
```

### Run
```bash
mvn spring-boot:run
```

### Access
- Backend API: `http://localhost:8080`
- Actuator (health check): `http://localhost:8080/actuator/health`

---

## 📊 Database Schema

### orders table
```sql
CREATE TABLE orders (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  external_order_id VARCHAR(255) UNIQUE NOT NULL,
  product_name VARCHAR(255) NOT NULL,
  amount DECIMAL(19, 2) NOT NULL,
  currency_code VARCHAR(3) NOT NULL,
  status VARCHAR(50) NOT NULL,
  created_at TIMESTAMP,
  updated_at TIMESTAMP
);
```

---

## 🔍 Key Design Patterns

### 1. **Repository Pattern**
- `OrderRepository` abstracts database access
- Uses Spring Data JPA for CRUD operations

### 2. **Service Layer Pattern**
- `PaypalService` handles PayPal API integration logic
- `ProductService` manages product data
- Separation of concerns between controllers and business logic

### 3. **DTO Pattern (Data Transfer Objects)**
- `CreateOrderRequest/Response` - API contracts
- `CaptureOrderResponse` - Standardized response format
- Decouples API from internal models

### 4. **Dependency Injection**
- Spring manages bean creation and lifecycle
- Constructor injection for better testability

### 5. **Error Handling**
- `GlobalExceptionHandler` for centralized exception management
- Custom exception messages for different failure scenarios
- Graceful handling of idempotent operations (duplicate captures)

---

## 🧪 Testing Scenarios

### Happy Path
```
1. List products ✓
2. Create order ✓
3. Approve on PayPal ✓
4. Capture payment ✓
5. Order marked COMPLETED ✓
```

### Edge Cases
```
1. Invalid product ID → 400 Bad Request
2. Duplicate capture → Detects and returns success
3. Failed payment → 500 error with reason, order marked FAILED
4. Network timeout → WebClient handles with circuit breaker
5. Missing order ID parameter → 400 Bad Request
```

---

## 📈 Future Enhancements

1. **Authentication/Authorization** - Secure user accounts
2. **Order History** - Query orders by user
3. **Refund Processing** - Handle payment refunds
4. **Payment Methods** - Support credit cards, digital wallets
5. **Inventory Management** - Track product stock
6. **Notifications** - Email/SMS payment confirmations
7. **Analytics** - Track sales, conversion rates
8. **Admin Dashboard** - Order management interface
9. **Subscription Support** - Recurring billing
10. **Multi-currency** - Support different currencies per user

---

## 📄 Files Structure

```
src/main/java/com/example/ecommerce/
├── EcommerceApplication.java          # Spring Boot entry point
├── config/
│   ├── CorsConfig.java                # CORS configuration
│   └── PaypalConfig.java              # PayPal WebClient setup
├── exception/
│   └── GlobalExceptionHandler.java    # Centralized error handling
├── order/
│   ├── Order.java                     # JPA entity
│   ├── OrderRepository.java           # Data access
│   └── OrderStatus.java               # Enum: PENDING, COMPLETED, FAILED
├── paypal/
│   ├── PaypalController.java          # API endpoints
│   ├── PaypalService.java             # Business logic
│   └── dto/
│       ├── CreateOrderRequest.java    # Request DTO
│       ├── CreateOrderResponse.java   # Response DTO
│       └── CaptureOrderResponse.java  # Response DTO
└── product/
    ├── Product.java                   # Model
    ├── ProductController.java         # API endpoint
    └── ProductService.java            # Business logic
```

---

## 🎯 Summary

This e-commerce backend demonstrates a **complete, production-ready integration** with PayPal's REST API:

✅ **Product Browsing** - Users can view available items
✅ **Order Creation** - Backend creates PayPal orders with proper setup
✅ **Payment Authorization** - Users approve payments on PayPal
✅ **Payment Capture** - Backend captures authorized payments
✅ **Error Handling** - Graceful handling of edge cases and failures
✅ **Database Persistence** - Orders stored with status tracking
✅ **Security** - OAuth2 authentication, input validation
✅ **Logging** - Comprehensive logging for debugging and auditing

The system is designed to be **scalable, maintainable, and production-ready** with proper separation of concerns, error handling, and security best practices.

