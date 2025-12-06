package com.example.loanapp;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LoanApplicationRepository extends JpaRepository<LoanApplication, Long> {
    Optional<LoanApplication> findByTrackingId(String trackingId);
    Optional<LoanApplication> findByCheckoutRequestID(String checkoutRequestID);
}
