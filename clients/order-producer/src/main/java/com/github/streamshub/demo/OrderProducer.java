package com.github.streamshub.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

import java.time.Instant;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

public class OrderProducer {

    static final String PII_TOPIC = "pii.orders";
    static final String PUBLIC_TOPIC = "public.order-events";

    static final String[] NAMES = {
        "Jane Smith", "Carlos Rivera", "Aisha Patel", "Liam O'Brien",
        "Mei Chen", "Fatima Al-Rashid", "Dmitri Volkov", "Priya Sharma"
    };
    static final String[] EMAILS = {
        "jane.smith@example.com", "carlos.r@example.com", "aisha.p@example.com",
        "liam.ob@example.com", "mei.chen@example.com", "fatima.ar@example.com",
        "dmitri.v@example.com", "priya.s@example.com"
    };
    static final String[] ADDRESSES = {
        "123 Main St, Springfield, IL 62701",
        "456 Oak Ave, Portland, OR 97201",
        "789 Pine Rd, Austin, TX 78701",
        "321 Elm St, Denver, CO 80201",
        "654 Maple Dr, Seattle, WA 98101",
        "987 Cedar Ln, Boston, MA 02101",
        "246 Birch Ct, Miami, FL 33101",
        "135 Willow Way, Chicago, IL 60601"
    };
    static final String[] EVENT_TYPES = {
        "ORDER_PLACED", "ORDER_CONFIRMED", "ORDER_SHIPPED", "ORDER_DELIVERED"
    };

    public static void main(String[] args) throws Exception {
        String bootstrapServers = env("BOOTSTRAP_SERVERS", "localhost:9094");
        String tokenEndpoint = env("TOKEN_ENDPOINT", "http://localhost:8080/realms/kafka-oauth/protocol/openid-connect/token");
        String clientId = env("CLIENT_ID", "order-producer");
        String clientSecret = env("CLIENT_SECRET", "order-producer-secret");

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");

        // OAuth / SASL configuration
        props.put("security.protocol", "SASL_PLAINTEXT");
        props.put("sasl.mechanism", "OAUTHBEARER");
        props.put("sasl.jaas.config",
            "org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required"
            + " oauth.token.endpoint.uri=\"" + tokenEndpoint + "\""
            + " oauth.client.id=\"" + clientId + "\""
            + " oauth.client.secret=\"" + clientSecret + "\" ;");
        props.put("sasl.login.callback.handler.class",
            "io.strimzi.kafka.oauth.client.JaasClientOauthLoginCallbackHandler");

        ObjectMapper mapper = new ObjectMapper();
        Random random = new Random();
        AtomicInteger orderCounter = new AtomicInteger(1);

        System.out.println("Starting OrderProducer...");
        System.out.println("  Bootstrap: " + bootstrapServers);
        System.out.println("  Token endpoint: " + tokenEndpoint);
        System.out.println("  Client ID: " + clientId);

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            while (true) {
                int idx = random.nextInt(NAMES.length);
                String orderId = String.format("ORD-%05d", orderCounter.getAndIncrement());
                double amount = Math.round((10 + random.nextDouble() * 490) * 100.0) / 100.0;
                String timestamp = Instant.now().toString();
                String eventType = EVENT_TYPES[random.nextInt(EVENT_TYPES.length)];

                // PII record (contains personal data)
                ObjectNode piiRecord = mapper.createObjectNode();
                piiRecord.put("orderId", orderId);
                piiRecord.put("customerName", NAMES[idx]);
                piiRecord.put("customerEmail", EMAILS[idx]);
                piiRecord.put("customerAddress", ADDRESSES[idx]);
                piiRecord.put("amount", amount);
                piiRecord.put("timestamp", timestamp);

                producer.send(new ProducerRecord<>(PII_TOPIC, orderId, mapper.writeValueAsString(piiRecord)),
                    (metadata, exception) -> {
                        if (exception != null) {
                            System.err.println("Failed to send PII record: " + exception.getMessage());
                        } else {
                            System.out.println("[pii.orders] " + orderId + " -> partition " + metadata.partition());
                        }
                    });

                // Public record (no personal data)
                ObjectNode publicRecord = mapper.createObjectNode();
                publicRecord.put("orderId", orderId);
                publicRecord.put("amount", amount);
                publicRecord.put("timestamp", timestamp);
                publicRecord.put("eventType", eventType);

                producer.send(new ProducerRecord<>(PUBLIC_TOPIC, orderId, mapper.writeValueAsString(publicRecord)),
                    (metadata, exception) -> {
                        if (exception != null) {
                            System.err.println("Failed to send public record: " + exception.getMessage());
                        } else {
                            System.out.println("[public.order-events] " + orderId + " -> partition " + metadata.partition());
                        }
                    });

                Thread.sleep(1000);
            }
        }
    }

    static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value != null ? value : defaultValue;
    }
}
