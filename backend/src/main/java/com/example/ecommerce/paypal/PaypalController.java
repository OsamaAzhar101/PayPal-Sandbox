package com.example.ecommerce.paypal;

import com.example.ecommerce.paypal.dto.CaptureOrderResponse;
import com.example.ecommerce.paypal.dto.CreateOrderRequest;
import com.example.ecommerce.paypal.dto.CreateOrderResponse;
import com.example.ecommerce.product.Product;
import com.example.ecommerce.product.ProductService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/paypal")
public class PaypalController {

    private final PaypalService paypalService;
    private final ProductService productService;

    public PaypalController(PaypalService paypalService, ProductService productService) {
        this.paypalService = paypalService;
        this.productService = productService;
    }

    @PostMapping("/create-order")
    public ResponseEntity<CreateOrderResponse> createOrder(@Valid @RequestBody CreateOrderRequest request) {
        Product product = productService.getById(request.getProductId())
                .orElseThrow(() -> new IllegalArgumentException("Invalid product id"));

        CreateOrderResponse response = paypalService.createOrderForProduct(product);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/capture-order")
    public ResponseEntity<CaptureOrderResponse> captureOrder(@RequestParam("orderId") String paypalOrderId) {
        CaptureOrderResponse response = paypalService.captureOrder(paypalOrderId);
        return ResponseEntity.ok(response);
    }
}

