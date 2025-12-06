package com.example.loanapp;

public class StkPushRequest {
    private String trackingId;
    private String phone;
    private int amount;

    // getters & setters
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public int getAmount() { return amount; }
    public void setAmount(int amount) { this.amount = amount; }

    public String getTrackingId() {
        return trackingId;
    }

    public void setTrackingId(String trackingId) {
        this.trackingId = trackingId;
    }
}
