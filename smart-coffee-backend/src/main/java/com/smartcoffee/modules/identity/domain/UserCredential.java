package com.smartcoffee.modules.identity.domain;
import jakarta.persistence.*; import java.time.Instant; import java.util.UUID;
@Entity @Table(schema="identity",name="user_credentials")
public class UserCredential {
 @Id @Column(name="user_id") private UUID userId;
 @Column(name="password_hash",nullable=false) private String passwordHash;
 @Column(name="failed_login_count",nullable=false) private int failedLoginCount;
 @Column(name="locked_until") private Instant lockedUntil;
 @Column(name="password_changed_at",nullable=false) private Instant passwordChangedAt;
 protected UserCredential(){}
 public UserCredential(UUID userId,String hash){this.userId=userId;this.passwordHash=hash;this.passwordChangedAt=Instant.now();}
 public UUID userId(){return userId;} public String passwordHash(){return passwordHash;} public boolean locked(){return lockedUntil!=null&&lockedUntil.isAfter(Instant.now());}
 public void success(){failedLoginCount=0;lockedUntil=null;} public void failure(){failedLoginCount++;if(failedLoginCount>=5)lockedUntil=Instant.now().plusSeconds(900);}
}

