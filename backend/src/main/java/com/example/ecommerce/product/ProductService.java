package com.example.ecommerce.product;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Service
public class ProductService {

    private final List<Product> products = Arrays.asList(
            new Product(1L, "Mock T-Shirt", new BigDecimal("19.99")),
            new Product(2L, "Mock Hoodie", new BigDecimal("39.99")),
            new Product(3L, "Mock Sneakers", new BigDecimal("59.99"))
    );

    public List<Product> getAllProducts() {
        return products;
    }

    public Optional<Product> getById(Long id) {
        return products.stream().filter(p -> p.getId().equals(id)).findFirst();
    }
}

