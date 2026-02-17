package com.example.loanapp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.client.RestTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/loans")
@CrossOrigin(
        origins = {
                "http://localhost:3000",
                "https://kopesha.vercel.app"
        },
        allowedHeaders = "*",
        methods = {
                RequestMethod.GET,
                RequestMethod.POST,
                RequestMethod.PUT,
                RequestMethod.DELETE,
                RequestMethod.OPTIONS
        }
)

public class LoanApplicationController {

    @Autowired
    private LoanApplicationRepository repository;

    private Random random = new Random();
    private RestTemplate restTemplate = new RestTemplate();
    private ObjectMapper objectMapper = new ObjectMapper();

    // =====================================================================
    // OLD SAFARICOM / DARAJA CREDENTIALS (commented out — kept for reference)
    // =====================================================================
    // private final String consumerKey = EnvConfig.dotenv.get("MPESA_CONSUMER_KEY");
    // private final String consumerSecret = EnvConfig.dotenv.get("MPESA_CONSUMER_SECRET");
    // private final String shortcode = EnvConfig.dotenv.get("MPESA_SHORTCODE");
    // private final String passkey = EnvConfig.dotenv.get("MPESA_PASSKEY");
    // private final String callbackUrl = EnvConfig.dotenv.get("MPESA_CALLBACK_URL");

    // =====================================================================
    // PAYHERO CREDENTIALS
    // Add these to your .env file:
    //   PAYHERO_API_USERNAME=zrFinMcH60MMV8mKVFwq
    //   PAYHERO_API_PASSWORD=your_password_here
    //   PAYHERO_CHANNEL_ID=your_channel_id_here
    //   PAYHERO_CALLBACK_URL=https://kopesa.onrender.com/api/loans/mpesa/callback
    // =====================================================================
    private final String payHeroUsername = EnvConfig.dotenv.get("PAYHERO_API_USERNAME");
    private final String payHeroPassword = EnvConfig.dotenv.get("PAYHERO_API_PASSWORD");
    private final String payHeroChannelId = EnvConfig.dotenv.get("PAYHERO_CHANNEL_ID");
    private final String callbackUrl = EnvConfig.dotenv.get("PAYHERO_CALLBACK_URL");

    // PayHero STK Push endpoint
    private static final String PAYHERO_STK_URL = "https://backend.payhero.co.ke/api/v2/payments";

    // Map to store payment statuses
    private static final Map<String, PaymentStatus> paymentStatusMap = new ConcurrentHashMap<>();

    @Autowired
    private LoanApplicationRepository loanApplicationRepository;

    // Inner class to track payment details
    private static class PaymentStatus {
        String status; // pending, success, cancelled, failed
        long timestamp;
        String resultDesc;

        PaymentStatus(String status, String resultDesc) {
            this.status = status;
            this.resultDesc = resultDesc;
            this.timestamp = System.currentTimeMillis();
        }
    }

    // =========================================================
    // Helper: build PayHero Basic Auth header
    // PayHero uses Basic Auth (base64 of "username:password")
    // on every request — no separate token fetch needed.
    // =========================================================
    private String getPayHeroBasicAuth() {
        String credentials = payHeroUsername + ":" + payHeroPassword;
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes());
    }

    @PostMapping("/apply")
    public LoanApplication applyLoan(@RequestBody LoanApplication application) {
        application.setStatus("PENDING");
        application.setApplicationDate(new Date());

        // Generate random tracking ID: e.g., LON-C123456L9876543
        String trackingId = "LON-C" + (100000 + random.nextInt(900000))
                + "L" + (1000000 + random.nextInt(9000000));
        application.setTrackingId(trackingId);

        return repository.save(application);
    }

    @PostMapping("/stk-push")
    public ResponseEntity<Map<String, Object>> initiateStkPush(@RequestBody StkPushRequest request) {
        try {
            // 1. Find loan by trackingId
            Optional<LoanApplication> loanOptional = repository.findByTrackingId(request.getTrackingId());
            if (loanOptional.isEmpty()) {
                System.err.println("Loan not found for trackingId: " + request.getTrackingId());
                return ResponseEntity.status(404).body(Map.of(
                        "error", "Loan not found for trackingId: " + request.getTrackingId()
                ));
            }
            LoanApplication loan = loanOptional.get();

            // Save selected values from frontend
            loan.setLoanAmount(request.getLoanAmount());
            loan.setVerificationFee(request.getVerificationFee());
            if (loan.getStatus() == null || loan.getStatus().equals("NEW")) {
                loan.setStatus("PENDING");
            }

            // 2. Format phone
            String phone = formatPhone(request.getPhone());
            System.out.println("Initiating PayHero STK Push for phone: " + phone + ", loan: " + loan.getTrackingId());

            // =====================================================================
            // OLD SAFARICOM STK PUSH BLOCK (commented out — kept for reference)
            // =====================================================================
            /*
            // 3. Get Safaricom Access Token
            String auth = consumerKey + ":" + consumerSecret;
            String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes());

            HttpHeaders tokenHeaders = new HttpHeaders();
            tokenHeaders.set("Authorization", "Basic " + encodedAuth);

            System.out.println("Requesting MPESA OAuth token...");
            ResponseEntity<Map> tokenRes = restTemplate.exchange(
                    "https://api.safaricom.co.ke/oauth/v1/generate?grant_type=client_credentials",
                    HttpMethod.GET,
                    new HttpEntity<>(tokenHeaders),
                    Map.class
            );
            System.out.println("OAuth token response: " + tokenRes.getBody());

            if (tokenRes.getBody() == null || !tokenRes.getBody().containsKey("access_token")) {
                System.err.println("No access token returned from MPESA OAuth");
                return ResponseEntity.status(500).body(Map.of("error", "Failed to get access token from MPESA"));
            }

            String accessToken = (String) tokenRes.getBody().get("access_token");

            // 4. Generate password and timestamp
            String timestamp = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
            String password = Base64.getEncoder().encodeToString((shortcode + passkey + timestamp).getBytes());

            // 5. Build Safaricom STK Push payload
            Map<String, Object> payload = new HashMap<>();
            payload.put("BusinessShortCode", shortcode);
            payload.put("Password", password);
            payload.put("Timestamp", timestamp);
            payload.put("TransactionType", "CustomerPayBillOnline");
            payload.put("Amount", request.getAmount());
            payload.put("PartyA", phone);
            payload.put("PartyB", shortcode);
            payload.put("PhoneNumber", phone);
            payload.put("CallBackURL", callbackUrl);
            payload.put("AccountReference", "Loan Verification");
            payload.put("TransactionDesc", "Verification Payment");

            System.out.println("STK Push payload: " + payload);

            // 6. Send Safaricom STK Push
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);
            headers.setContentType(MediaType.APPLICATION_JSON);

            ResponseEntity<String> stkRes = restTemplate.postForEntity(
                    "https://api.safaricom.co.ke/mpesa/stkpush/v1/processrequest",
                    new HttpEntity<>(payload, headers),
                    String.class
            );
            */
            // =====================================================================
            // END OLD SAFARICOM BLOCK
            // =====================================================================


            // =====================================================================
            // NEW PAYHERO STK PUSH BLOCK
            // =====================================================================

            // 3. Build PayHero STK Push payload
            Map<String, Object> payload = new HashMap<>();
            payload.put("amount", request.getAmount());
            payload.put("phone_number", phone);
            payload.put("channel_id", Integer.parseInt(payHeroChannelId));
            payload.put("provider", "m-pesa");
            payload.put("external_reference", loan.getTrackingId()); // use trackingId as reference
            payload.put("customer_name", loan.getName() != null ? loan.getName() : "Customer");
            payload.put("callback_url", callbackUrl);

            System.out.println("PayHero STK Push payload: " + payload);

            // 4. Send PayHero STK Push (Basic Auth — no separate token fetch needed)
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", getPayHeroBasicAuth());
            headers.setContentType(MediaType.APPLICATION_JSON);

            ResponseEntity<String> stkRes = restTemplate.postForEntity(
                    PAYHERO_STK_URL,
                    new HttpEntity<>(payload, headers),
                    String.class
            );

            // =====================================================================
            // END NEW PAYHERO BLOCK
            // =====================================================================

            String response = stkRes.getBody();
            System.out.println("PayHero STK Push raw response: " + response);

            // 5. Parse response
            JsonNode root = objectMapper.readTree(response);

            // Handle PayHero errors
            if (root.has("error") || (root.has("success") && !root.get("success").asBoolean())) {
                String errorMessage = root.has("error") ? root.get("error").asText()
                        : root.has("message") ? root.get("message").asText() : "Unknown error from PayHero";
                System.err.println("PayHero error: " + errorMessage);
                return ResponseEntity.status(400).body(Map.of(
                        "error", errorMessage,
                        "rawResponse", response
                ));
            }

            // PayHero returns CheckoutRequestID on success
            if (root.has("CheckoutRequestID")) {
                String checkoutRequestID = root.get("CheckoutRequestID").asText();

                loan.setCheckoutRequestID(checkoutRequestID);
                repository.save(loan);

                paymentStatusMap.put(checkoutRequestID, new PaymentStatus("pending", "PayHero STK Push sent"));

                System.out.println("PayHero STK Push successfully initiated for loan " + loan.getTrackingId()
                        + ", CheckoutRequestID: " + checkoutRequestID);

                return ResponseEntity.ok(Map.of(
                        "message", "STK Push sent successfully",
                        "checkoutRequestID", checkoutRequestID
                ));
            }

            // Unknown response
            System.err.println("Unknown PayHero response: " + response);
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Unknown response from PayHero",
                    "rawResponse", response
            ));

        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("STK Push failed for loan " + request.getTrackingId() + ": " + e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", "STK Push failed: " + e.getMessage()));
        }
    }

    @GetMapping("/all")
    public List<LoanApplication> getAllLoans() {
        return repository.findAll();
    }

    // =====================================================================
    // OLD sendStkPush() — Safaricom direct (commented out, kept for reference)
    // =====================================================================
    /*
    private String sendStkPush(String phone, int amount) {
        try {
            // 1. Get Access Token
            String auth = consumerKey + ":" + consumerSecret;
            String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes());

            HttpHeaders tokenHeaders = new HttpHeaders();
            tokenHeaders.set("Authorization", "Basic " + encodedAuth);

            ResponseEntity<Map> tokenRes = restTemplate.exchange(
                    "https://api.safaricom.co.ke/oauth/v1/generate?grant_type=client_credentials",
                    HttpMethod.GET,
                    new HttpEntity<>(tokenHeaders),
                    Map.class
            );

            String accessToken = (String) tokenRes.getBody().get("access_token");

            // 2. Generate password and timestamp
            String timestamp = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
            String password = Base64.getEncoder().encodeToString(
                    (shortcode + passkey + timestamp).getBytes()
            );

            // 3. Format phone number
            phone = formatPhone(phone);

            // 4. Build STK payload
            Map<String, Object> payload = new HashMap<>();
            payload.put("BusinessShortCode", shortcode);
            payload.put("Password", password);
            payload.put("Timestamp", timestamp);
            payload.put("TransactionType", "CustomerPayBillOnline");
            payload.put("Amount", amount);
            payload.put("PartyA", phone);
            payload.put("PartyB", shortcode);
            payload.put("PhoneNumber", phone);
            payload.put("CallBackURL", callbackUrl);
            payload.put("AccountReference", "Loan Verification");
            payload.put("TransactionDesc", "Verification Payment");

            // 5. Send STK Push
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);
            headers.setContentType(MediaType.APPLICATION_JSON);

            ResponseEntity<String> stkRes = restTemplate.postForEntity(
                    "https://api.safaricom.co.ke/mpesa/stkpush/v1/processrequest",
                    new HttpEntity<>(payload, headers),
                    String.class
            );

            String response = stkRes.getBody();

            try {
                JsonNode root = objectMapper.readTree(response);
                String checkoutRequestID = root.get("CheckoutRequestID").asText();

                Optional<LoanApplication> loanOptional = repository.findByCheckoutRequestID(checkoutRequestID);
                if (loanOptional.isPresent()) {
                    LoanApplication loan = loanOptional.get();
                    loan.setCheckoutRequestID(checkoutRequestID);
                    repository.save(loan);
                }

                paymentStatusMap.put(checkoutRequestID, new PaymentStatus("pending", "STK Push sent"));
                System.out.println("STK Push initiated: " + checkoutRequestID);
            } catch (Exception e) {
                System.err.println("Could not extract CheckoutRequestID: " + e.getMessage());
            }

            return response;

        } catch (Exception e) {
            e.printStackTrace();
            return "{\"error\": \"STK Push failed: " + e.getMessage() + "\"}";
        }
    }
    */

    private String formatPhone(String phone) {
        phone = phone.replace("+", "").replace(" ", "");

        if (phone.startsWith("0")) {
            return "254" + phone.substring(1);
        }
        if (phone.startsWith("7")) {
            return "254" + phone;
        }
        if (phone.startsWith("254")) {
            return phone;
        }

        throw new RuntimeException("Invalid phone number format: " + phone);
    }

    // =====================================================================
    // CALLBACK — Updated to handle PayHero's callback format
    //
    // PayHero sends a POST with this structure:
    // {
    //   "CheckoutRequestID": "ws_CO_...",
    //   "ExternalReference":  "LON-C123456L9876543",
    //   "ResultCode":         0,           // 0 = success
    //   "ResultDesc":         "The service request is processed successfully.",
    //   "Amount":             100,
    //   "MpesaReceiptNumber": "RDK7TF0WBN"
    // }
    // =====================================================================
    @PostMapping("/mpesa/callback")
    public ResponseEntity<Map<String, Object>> mpesaCallback(@RequestBody Map<String, Object> payload) {
        try {
            System.out.println("PayHero Callback received: " + payload);

            if (payload == null) {
                System.err.println("Invalid callback payload: null body");
                return ResponseEntity.status(400).body(Map.of("error", "Invalid callback payload"));
            }

            // First, try to get 'response' map if it exists
            Map<String, Object> responseMap = payload.get("response") instanceof Map
                    ? (Map<String, Object>) payload.get("response")
                    : null;

            // Extract CheckoutRequestID or fallback to User_Reference
            String checkoutRequestID = payload.get("CheckoutRequestID") != null
                    ? payload.get("CheckoutRequestID").toString()
                    : responseMap != null && responseMap.get("CheckoutRequestID") != null
                    ? responseMap.get("CheckoutRequestID").toString()
                    : responseMap != null && responseMap.get("User_Reference") != null
                    ? responseMap.get("User_Reference").toString()
                    : null;

            if (checkoutRequestID == null) {
                System.err.println("Invalid PayHero callback: missing CheckoutRequestID/User_Reference");
                return ResponseEntity.status(400).body(Map.of("error", "Missing CheckoutRequestID/User_Reference"));
            }

            // Extract ResultCode
            Integer resultCode = null;
            if (responseMap != null && responseMap.get("ResultCode") != null) {
                resultCode = responseMap.get("ResultCode") instanceof Integer
                        ? (Integer) responseMap.get("ResultCode")
                        : Integer.parseInt(responseMap.get("ResultCode").toString());
            } else if (payload.get("ResultCode") != null) {
                resultCode = payload.get("ResultCode") instanceof Integer
                        ? (Integer) payload.get("ResultCode")
                        : Integer.parseInt(payload.get("ResultCode").toString());
            } else {
                resultCode = -1; // unknown
            }

            // Extract ResultDesc
            String resultDesc = null;
            if (responseMap != null && responseMap.get("ResultDesc") != null) {
                resultDesc = responseMap.get("ResultDesc").toString();
            } else if (payload.get("ResultDesc") != null) {
                resultDesc = payload.get("ResultDesc").toString();
            } else if (responseMap != null && responseMap.get("Status") != null) {
                // Some callbacks use "Status" field instead
                resultDesc = responseMap.get("Status").toString();
            } else {
                resultDesc = "No description";
            }

            // Find loan and update status
            Optional<LoanApplication> loanOptional = repository.findByCheckoutRequestID(checkoutRequestID);

            if (loanOptional.isPresent()) {
                LoanApplication loan = loanOptional.get();

                switch (resultCode) {
                    case 0 -> {
                        loan.setStatus("PAID");
                        paymentStatusMap.put(checkoutRequestID, new PaymentStatus("success", resultDesc));
                        System.out.println("Payment successful for loan " + loan.getTrackingId());
                    }
                    case 1032 -> {
                        loan.setStatus("CANCELLED");
                        paymentStatusMap.put(checkoutRequestID, new PaymentStatus("cancelled", resultDesc));
                        System.out.println("Payment cancelled for loan " + loan.getTrackingId());
                    }
                    default -> {
                        loan.setStatus("FAILED");
                        paymentStatusMap.put(checkoutRequestID, new PaymentStatus("failed", resultDesc));
                        System.out.println("Payment failed for loan " + loan.getTrackingId()
                                + ", ResultCode: " + resultCode + ", Desc: " + resultDesc);
                    }
                }

                repository.save(loan);
            } else {
                System.err.println("Loan not found for CheckoutRequestID/User_Reference: " + checkoutRequestID);
            }

            return ResponseEntity.ok(Map.of("message", "Callback processed"));

        } catch (Exception e) {
            System.err.println("Error processing PayHero callback: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(500).body(Map.of("error", "Callback processing failed"));
        }
    }


    @GetMapping("/mpesa/status/{checkoutRequestID}")
    public ResponseEntity<?> getPaymentStatus(@PathVariable String checkoutRequestID) {
        Optional<LoanApplication> loanOptional =
                loanApplicationRepository.findByCheckoutRequestID(checkoutRequestID);

        if (loanOptional.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                    "status", "error",
                    "message", "Loan not found"
            ));
        }

        LoanApplication loan = loanOptional.get();

        return ResponseEntity.ok(Map.of(
                "status", loan.getStatus(),
                "message", "Status fetched successfully"
        ));
    }

    // Delete a loan by its tracking ID
    @DeleteMapping("/delete/{trackingId}")
    @CrossOrigin(origins = "https://kopesha.vercel.app")
    public ResponseEntity<Map<String, String>> deleteLoan(@PathVariable String trackingId) {
        Optional<LoanApplication> loanOptional = repository.findByTrackingId(trackingId);

        if (loanOptional.isPresent()) {
            LoanApplication loan = loanOptional.get();
            repository.delete(loan);

            if (loan.getCheckoutRequestID() != null) {
                paymentStatusMap.remove(loan.getCheckoutRequestID());
            }

            return ResponseEntity.ok(Map.of(
                    "message", "Loan deleted successfully",
                    "trackingId", trackingId
            ));
        } else {
            return ResponseEntity.status(404).body(Map.of(
                    "error", "Loan not found",
                    "trackingId", trackingId
            ));
        }
    }

    @PostMapping("/verify-message")
    public ResponseEntity<Map<String, Object>> saveMpesaMessage(@RequestBody Map<String, String> payload) {
        try {
            String trackingId = payload.get("trackingId");
            String mpesaMessage = payload.get("mpesaMessage");

            if (trackingId == null || mpesaMessage == null || mpesaMessage.isEmpty()) {
                return ResponseEntity.status(400).body(Map.of("error", "Missing trackingId or mpesaMessage"));
            }

            Optional<LoanApplication> loanOptional = repository.findByTrackingId(trackingId);

            if (loanOptional.isEmpty()) {
                return ResponseEntity.status(404).body(Map.of("error", "Loan not found"));
            }

            LoanApplication loan = loanOptional.get();
            loan.setMpesaMessage(mpesaMessage);
            loan.setMpesaMessageDate(new Date());
            repository.save(loan);

            return ResponseEntity.ok(Map.of(
                    "message", "M-Pesa message saved successfully",
                    "trackingId", trackingId
            ));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(Map.of("error", "Server error"));
        }
    }

    @GetMapping("/mpesa-messages")
    public List<Map<String, Object>> getAllMpesaMessages() {
        return repository.findAll().stream()
                .filter(l -> l.getMpesaMessage() != null)
                .map(l -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("trackingId", l.getTrackingId());
                    map.put("name", l.getName());
                    map.put("phone", l.getPhone());
                    map.put("mpesaMessage", l.getMpesaMessage());
                    map.put("date", l.getMpesaMessageDate());
                    map.put("status", l.getStatus());
                    return map;
                })
                .toList();
    }

    @PutMapping("/update-offer")
    public ResponseEntity<Map<String, String>> updateLoanOffer(@RequestBody Map<String, Object> payload) {
        String trackingId = (String) payload.get("trackingId");

        Optional<LoanApplication> optionalLoan = repository.findByTrackingId(trackingId);
        if (optionalLoan.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "Loan not found"));
        }

        LoanApplication loan = optionalLoan.get();

        if (payload.get("loanAmount") instanceof Number) {
            loan.setLoanAmount(((Number) payload.get("loanAmount")).intValue());
        }

        if (payload.get("verificationFee") instanceof Number) {
            loan.setVerificationFee(((Number) payload.get("verificationFee")).intValue());
        }

        repository.save(loan);

        return ResponseEntity.ok(Map.of("message", "Loan offer saved"));
    }
}