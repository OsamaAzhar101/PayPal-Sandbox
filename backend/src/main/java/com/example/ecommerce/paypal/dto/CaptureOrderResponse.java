package com.example.ecommerce.paypal.dto;

import java.math.BigDecimal;

public class CaptureOrderResponse {

    private String status;
    private String productName;
    private BigDecimal amount;
    private String currencyCode;

    public CaptureOrderResponse(String status, String productName, BigDecimal amount, String currencyCode) {
        this.status = status;
        this.productName = productName;
        this.amount = amount;
        this.currencyCode = currencyCode;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public void setCurrencyCode(String currencyCode) {
        this.currencyCode = currencyCode;
    }
}

