package com.smartcoffee.modules.auth.persistence;
import com.smartcoffee.modules.auth.domain.AuthSession; import java.util.*; import org.springframework.data.jpa.repository.*; import org.springframework.data.repository.query.Param;
public interface AuthSessionRepository extends JpaRepository<AuthSession,UUID>{Optional<AuthSession> findByRefreshTokenHash(String hash); @Modifying @Query("update AuthSession s set s.revokedAt=CURRENT_TIMESTAMP where s.userId=:userId and s.revokedAt is null") int revokeAll(@Param("userId")UUID userId); @Modifying @Query("update AuthSession s set s.revokedAt=CURRENT_TIMESTAMP where s.tokenFamilyId=:family and s.revokedAt is null") int revokeFamily(@Param("family")UUID familyId);}

