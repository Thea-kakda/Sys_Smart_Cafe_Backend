package com.smartcoffee.modules.identity.persistence;
import com.smartcoffee.modules.identity.domain.*; import java.util.*; import org.springframework.data.jpa.repository.JpaRepository;
public interface UserRepository extends JpaRepository<User,UUID>{Optional<User> findByNormalizedEmail(String email); boolean existsByNormalizedEmail(String email);}

