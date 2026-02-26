package com.example.ecommerce.paypal.dto;

public class CreateOrderResponse {

    private String paypalOrderId;
    private String approvalUrl;

    public CreateOrderResponse(String paypalOrderId, String approvalUrl) {
        this.paypalOrderId = paypalOrderId;
        this.approvalUrl = approvalUrl;
    }

    public String getPaypalOrderId() {
        return paypalOrderId;
    }

    public void setPaypalOrderId(String paypalOrderId) {
        this.paypalOrderId = paypalOrderId;
    }

    public String getApprovalUrl() {
        return approvalUrl;
    }

    public void setApprovalUrl(String approvalUrl) {
        this.approvalUrl = approvalUrl;
    }
}

