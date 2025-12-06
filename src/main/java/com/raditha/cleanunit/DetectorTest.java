package com.raditha.cleanunit;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Simple test to verify LiveConnectionDetector can detect Kafka in
 * csi-bm-approval-java-service
 */
public class DetectorTest {
    public static void main(String[] args) {
        LiveConnectionDetector detector = new LiveConnectionDetector();
        Path projectRoot = Paths.get("/home/raditha/csi/Antikythera/csi-bm-approval-java-service");

        // Test the kafka URL detection with the actual value from the properties file
        String kafkaUrl = "172.15.100.145:9092,172.15.100.145:9093,172.15.100.145:9094,172.15.100.210:9092,172.15.100.210:9093,172.15.100.210:9094,172.15.100.211:9092,172.15.100.211:9093,172.15.100.211:9094";

        System.out.println("Testing Kafka URL detection:");
        System.out.println("URL: " + kafkaUrl);

        // Use reflection to call the private method for testing
        try {
            java.lang.reflect.Method method = LiveConnectionDetector.class.getDeclaredMethod("isLiveKafkaUrl",
                    String.class);
            method.setAccessible(true);
            boolean result = (boolean) method.invoke(detector, kafkaUrl);
            System.out.println("Detection result: " + result);

            if (result) {
                System.out.println("✓ SUCCESS: Kafka connection detected!");
            } else {
                System.out.println("✗ FAILED: Kafka connection NOT detected!");
            }
        } catch (Exception e) {
            System.err.println("Error testing detection: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
