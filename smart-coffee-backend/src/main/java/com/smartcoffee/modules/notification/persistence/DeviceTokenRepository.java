package com.smartcoffee.modules.notification.persistence;

import com.smartcoffee.modules.notification.domain.DeviceToken;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceTokenRepository extends JpaRepository<DeviceToken, UUID> {
    List<DeviceToken> findByUserIdAndActiveTrue(UUID user);
}

