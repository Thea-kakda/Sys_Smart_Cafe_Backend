package com.smartcoffee.modules.identity.persistence;
import com.smartcoffee.modules.identity.domain.UserCredential; import java.util.UUID; import org.springframework.data.jpa.repository.JpaRepository;
public interface UserCredentialRepository extends JpaRepository<UserCredential,UUID>{}

