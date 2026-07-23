package edu.bu.archive.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration
public class AwsConfiguration {

    @Bean
    S3Client s3Client(
            @Value("${AWS_REGION:us-east-1}")
            String awsRegion
    ) {
        return S3Client.builder()
                .region(Region.of(awsRegion))
                .build();
    }
}
