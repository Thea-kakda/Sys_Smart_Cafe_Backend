package com.smartcoffee.modules.auth.security;
import java.nio.charset.StandardCharsets; import java.security.*; import java.util.*; import org.springframework.stereotype.Component;
@Component public class RefreshTokenService { private final SecureRandom random=new SecureRandom(); public String issue(){byte[] b=new byte[48];random.nextBytes(b);return Base64.getUrlEncoder().withoutPadding().encodeToString(b);} public String hash(String token){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}} }

