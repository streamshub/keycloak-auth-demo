package com.github.streamshub.demo;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.time.Duration;
import java.util.List;
import java.util.Properties;

public class OrderConsumer {

    static final String PII_TOPIC = "pii.orders";
    static final String PUBLIC_TOPIC = "public.order-events";

    public static void main(String[] args) {
        String bootstrapServers = env("BOOTSTRAP_SERVERS", "localhost:9094");
        String tokenEndpoint = env("TOKEN_ENDPOINT", "http://localhost:8080/realms/kafka-oauth/protocol/openid-connect/token");
        String clientId = env("CLIENT_ID", "order-consumer");
        String clientSecret = env("CLIENT_SECRET", "order-consumer-secret");
        String groupId = env("GROUP_ID", "order-consumer-group");

        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

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

        System.out.println("Starting OrderConsumer...");
        System.out.println("  Bootstrap: " + bootstrapServers);
        System.out.println("  Token endpoint: " + tokenEndpoint);
        System.out.println("  Client ID: " + clientId);
        System.out.println("  Group ID: " + groupId);

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(PII_TOPIC, PUBLIC_TOPIC));

            while (true) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(1));
                records.forEach(record -> {
                    System.out.printf("[%s] key=%s value=%s%n",
                        record.topic(), record.key(), record.value());
                });
            }
        }
    }

    static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value != null ? value : defaultValue;
    }
}
