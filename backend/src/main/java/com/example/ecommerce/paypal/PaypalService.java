package com.example.ecommerce.paypal;

import com.example.ecommerce.order.Order;
import com.example.ecommerce.order.OrderRepository;
import com.example.ecommerce.order.OrderStatus;
import com.example.ecommerce.paypal.dto.CaptureOrderResponse;
import com.example.ecommerce.paypal.dto.CreateOrderResponse;
import com.example.ecommerce.product.Product;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

@Service
public class PaypalService {

    private static final Logger log = LoggerFactory.getLogger(PaypalService.class);

    private final WebClient paypalWebClient;
    private final ObjectMapper objectMapper;
    private final OrderRepository orderRepository;

    @Value("${paypal.client-id}")
    private String clientId;

    @Value("${paypal.client-secret}")
    private String clientSecret;

    @Value("${paypal.currency-code}")
    private String currencyCode;

    @Value("${paypal.return-url:http://localhost:5173/checkout/success}")
    private String returnUrl;

    @Value("${paypal.cancel-url:http://localhost:5173/checkout/cancel}")
    private String cancelUrl;

    public PaypalService(WebClient paypalWebClient, ObjectMapper objectMapper, OrderRepository orderRepository) {
        this.paypalWebClient = paypalWebClient;
        this.objectMapper = objectMapper;
        this.orderRepository = orderRepository;
    }

    public CreateOrderResponse createOrderForProduct(Product product) {
        String accessToken = obtainAccessToken();

        Map<String, Object> amount = new HashMap<>();
        amount.put("currency_code", currencyCode);
        amount.put("value", product.getPrice().toString());

        Map<String, Object> purchaseUnit = new HashMap<>();
        purchaseUnit.put("amount", amount);
        purchaseUnit.put("description", product.getName());

        Map<String, Object> applicationContext = new HashMap<>();
        applicationContext.put("return_url", returnUrl);
        applicationContext.put("cancel_url", cancelUrl);

        Map<String, Object> payload = new HashMap<>();
        payload.put("intent", "CAPTURE");
        payload.put("purchase_units", new Object[]{purchaseUnit});
        payload.put("application_context", applicationContext);

        JsonNode response = paypalWebClient.post()
                .uri("/v2/checkout/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> headers.setBearerAuth(accessToken))
                .body(BodyInserters.fromValue(payload))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();

        if (response == null) {
            throw new IllegalStateException("Empty response from PayPal create order");
        }

        String paypalOrderId = response.get("id").asText();
        String approvalUrl = extractApprovalUrl(response);

        Order order = new Order();
        order.setExternalOrderId(paypalOrderId);
        order.setProductName(product.getName());
        order.setAmount(product.getPrice());
        order.setCurrencyCode(currencyCode);
        order.setStatus(OrderStatus.PENDING);
        order.setCreatedAt(OffsetDateTime.now());
        order.setUpdatedAt(OffsetDateTime.now());
        orderRepository.save(order);

        log.info("Created PayPal order {} for product {} amount {}", paypalOrderId, product.getName(), product.getPrice());

        return new CreateOrderResponse(paypalOrderId, approvalUrl);
    }

    public CaptureOrderResponse captureOrder(String paypalOrderId) {
        if (paypalOrderId == null || paypalOrderId.isBlank()) {
            throw new IllegalArgumentException("PayPal order ID is required");
        }
        String accessToken = obtainAccessToken();

        JsonNode response;
        try {
            response = paypalWebClient.post()
                    .uri("/v2/checkout/orders/{orderId}/capture", paypalOrderId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> headers.setBearerAuth(accessToken))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();
        } catch (WebClientResponseException e) {
            String body = e.getResponseBodyAsString();
            if (e.getStatusCode().value() == 422 && body != null && body.contains("ORDER_ALREADY_CAPTURED")) {
                log.info("Order {} already captured (e.g. duplicate request), fetching order details", paypalOrderId);
                response = fetchOrderDetails(paypalOrderId, accessToken);
                if (response != null) {
                    return buildCaptureResponseFromOrderResponse(response, paypalOrderId);
                }
            }
            log.error("PayPal capture failed: status={}, body={}", e.getStatusCode(), body);
            markOrderFailedIfPresent(paypalOrderId);
            String shortMessage = extractPayPalErrorMessage(body, e.getStatusCode().toString());
            throw new IllegalStateException(shortMessage, e);
        }

        if (response == null) {
            throw new IllegalStateException("Empty response from PayPal capture order");
        }

        return buildCaptureResponseFromOrderResponse(response, paypalOrderId);
    }

    private String obtainAccessToken() {
        String basicAuth = Base64.getEncoder().encodeToString((clientId + ":" + clientSecret).getBytes());

        JsonNode response = paypalWebClient.post()
                .uri("/v1/oauth2/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .header("Authorization", "Basic " + basicAuth)
                .body(BodyInserters.fromFormData("grant_type", "client_credentials"))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();

        if (response == null || response.get("access_token") == null) {
            throw new IllegalStateException("Unable to obtain PayPal access token");
        }

        return response.get("access_token").asText();
    }

    private String extractApprovalUrl(JsonNode orderResponse) {
        for (JsonNode link : orderResponse.withArray("links")) {
            if ("approve".equalsIgnoreCase(link.path("rel").asText())) {
                return link.path("href").asText();
            }
        }
        throw new IllegalStateException("Approval URL not found in PayPal response");
    }

    /**
     * GET /v2/checkout/orders/{id} - used when order was already captured (e.g. duplicate capture call).
     */
    private JsonNode fetchOrderDetails(String paypalOrderId, String accessToken) {
        try {
            return paypalWebClient.get()
                    .uri("/v2/checkout/orders/{orderId}", paypalOrderId)
                    .headers(headers -> headers.setBearerAuth(accessToken))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();
        } catch (Exception e) {
            log.warn("Failed to fetch order details for {}: {}", paypalOrderId, e.getMessage());
            return null;
        }
    }

    /**
     * Build capture response from a GET order or POST capture response (same structure).
     */
    private CaptureOrderResponse buildCaptureResponseFromOrderResponse(JsonNode response, String paypalOrderId) {
        String status = response.path("status").asText();
        JsonNode purchaseUnits = response.path("purchase_units");
        if (!purchaseUnits.isArray() || purchaseUnits.isEmpty()) {
            throw new IllegalStateException("Invalid PayPal order response: missing purchase_units");
        }
        JsonNode purchaseUnit = purchaseUnits.get(0);
        JsonNode payments = purchaseUnit.path("payments");
        JsonNode captures = payments.path("captures");
        if (!captures.isArray() || captures.isEmpty()) {
            throw new IllegalStateException("Invalid PayPal order response: missing captures");
        }
        JsonNode amountNode = captures.get(0).path("amount");
        String value = amountNode.path("value").asText();
        String currency = amountNode.path("currency_code").asText();
        BigDecimal paidAmount = new BigDecimal(value);
        String productName = purchaseUnit.has("description")
                ? purchaseUnit.path("description").asText()
                : "Purchase";

        Order order = orderRepository.findByExternalOrderId(paypalOrderId).orElse(null);
        if (order == null) {
            order = new Order();
            order.setExternalOrderId(paypalOrderId);
            order.setProductName(productName);
            order.setAmount(paidAmount);
            order.setCurrencyCode(currency);
            order.setCreatedAt(OffsetDateTime.now());
            log.info("Created order from PayPal (no local record): {} amount {}", paypalOrderId, paidAmount);
        } else if (paidAmount.compareTo(order.getAmount()) != 0) {
            log.warn("Amount mismatch for order {}: expected {}, got {}", paypalOrderId, order.getAmount(), paidAmount);
        }
        OrderStatus newStatus = "COMPLETED".equalsIgnoreCase(status) ? OrderStatus.COMPLETED : OrderStatus.FAILED;
        order.setStatus(newStatus);
        order.setUpdatedAt(OffsetDateTime.now());
        orderRepository.save(order);

        return new CaptureOrderResponse(status, order.getProductName(), paidAmount, currency);
    }

    private void markOrderFailedIfPresent(String paypalOrderId) {
        orderRepository.findByExternalOrderId(paypalOrderId).ifPresent(order -> {
            order.setStatus(OrderStatus.FAILED);
            order.setUpdatedAt(OffsetDateTime.now());
            orderRepository.save(order);
            log.info("Marked order {} as FAILED after PayPal capture error", paypalOrderId);
        });
    }

    private String extractPayPalErrorMessage(String body, String statusCode) {
        if (body == null || body.isBlank()) {
            return "PayPal capture failed: " + statusCode;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root.has("details") && root.get("details").isArray() && root.get("details").size() > 0) {
                JsonNode first = root.get("details").get(0);
                if (first.has("description")) {
                    return first.path("description").asText();
                }
            }
            if (root.has("message")) {
                return root.path("message").asText();
            }
            if (root.has("name")) {
                return "PayPal: " + root.path("name").asText();
            }
        } catch (Exception ignored) {
            // fall through
        }
        return body.length() > 200 ? body.substring(0, 200) + "..." : body;
    }
}

