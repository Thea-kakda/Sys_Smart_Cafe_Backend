package com.smartcoffee.modules.auth.security;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties(prefix="app.security.jwt") public record JwtProperties(String secret,long accessTokenMinutes,long refreshTokenDays){}

