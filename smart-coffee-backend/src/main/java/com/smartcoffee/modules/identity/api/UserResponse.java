package com.smartcoffee.modules.identity.api; 
import java.util.UUID; 
public record UserResponse(
    UUID userId,
    String fullName,
    String email,
    String preferredLanguage,
    String status){
    }

