package com.smartcoffee.infrastructure.storage;

import java.time.Instant;

public record ObjectMetadata(
        String bucket,
        String objectKey, 
        String contentType, 
        long sizeBytes, 
        String checksum,
        Instant createdAt
        ) {
                
                
}
