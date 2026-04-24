package com.github.streamshub.demo;

import io.apicurio.registry.serde.avro.AvroKafkaSerializer;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

import java.io.InputStream;
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
        String registryUrl = env("REGISTRY_URL", "http://localhost:8443");

        Schema piiSchema = loadSchema("avro/pii-order.avsc");
        Schema publicSchema = loadSchema("avro/public-order-event.avsc");

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, AvroKafkaSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");

        // OAuth / SASL configuration (for Kafka)
        props.put("security.protocol", "SASL_PLAINTEXT");
        props.put("sasl.mechanism", "OAUTHBEARER");
        props.put("sasl.jaas.config",
            "org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required"
            + " oauth.token.endpoint.uri=\"" + tokenEndpoint + "\""
            + " oauth.client.id=\"" + clientId + "\""
            + " oauth.client.secret=\"" + clientSecret + "\" ;");
        props.put("sasl.login.callback.handler.class",
            "io.strimzi.kafka.oauth.client.JaasClientOauthLoginCallbackHandler");

        // Apicurio Registry configuration
        props.put("apicurio.registry.url", registryUrl);
        props.put("apicurio.registry.auto-register", "true");
        props.put("apicurio.registry.artifact-resolver-strategy", TopicGroupStrategy.class.getName());
        props.put("apicurio.registry.auth.service.token.endpoint", tokenEndpoint);
        props.put("apicurio.registry.auth.client.id", clientId);
        props.put("apicurio.registry.auth.client.secret", clientSecret);

        Random random = new Random();
        AtomicInteger orderCounter = new AtomicInteger(1);

        System.out.println("Starting OrderProducer (Avro)...");
        System.out.println("  Bootstrap: " + bootstrapServers);
        System.out.println("  Registry: " + registryUrl);
        System.out.println("  Token endpoint: " + tokenEndpoint);
        System.out.println("  Client ID: " + clientId);

        try (KafkaProducer<String, GenericRecord> producer = new KafkaProducer<>(props)) {
            while (true) {
                int idx = random.nextInt(NAMES.length);
                String orderId = String.format("ORD-%05d", orderCounter.getAndIncrement());
                double amount = Math.round((10 + random.nextDouble() * 490) * 100.0) / 100.0;
                String timestamp = Instant.now().toString();
                String eventType = EVENT_TYPES[random.nextInt(EVENT_TYPES.length)];

                GenericRecord piiRecord = new GenericData.Record(piiSchema);
                piiRecord.put("orderId", orderId);
                piiRecord.put("customerName", NAMES[idx]);
                piiRecord.put("customerEmail", EMAILS[idx]);
                piiRecord.put("customerAddress", ADDRESSES[idx]);
                piiRecord.put("amount", amount);
                piiRecord.put("timestamp", timestamp);

                producer.send(new ProducerRecord<>(PII_TOPIC, orderId, piiRecord),
                    (metadata, exception) -> {
                        if (exception != null) {
                            System.err.println("Failed to send PII record: " + exception.getMessage());
                        } else {
                            System.out.println("[pii.orders] " + orderId + " -> partition " + metadata.partition());
                        }
                    });

                GenericRecord publicRecord = new GenericData.Record(publicSchema);
                publicRecord.put("orderId", orderId);
                publicRecord.put("amount", amount);
                publicRecord.put("timestamp", timestamp);
                publicRecord.put("eventType", eventType);

                producer.send(new ProducerRecord<>(PUBLIC_TOPIC, orderId, publicRecord),
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

    static Schema loadSchema(String resourcePath) throws Exception {
        try (InputStream is = OrderProducer.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) throw new RuntimeException("Schema not found: " + resourcePath);
            return new Schema.Parser().parse(is);
        }
    }

    static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value != null ? value : defaultValue;
    }
}
