package com.smartcoffee.modules.catalog.persistence;
import com.smartcoffee.modules.catalog.domain.CustomizationOption;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface CustomizationOptionRepository extends JpaRepository<CustomizationOption,UUID> { List<CustomizationOption> findByGroupIdAndActiveTrueOrderByDisplayOrderAscNameAsc(UUID groupId);
    /** Management read: every option of a group, inactive included, in editor order. */
    List<CustomizationOption> findByGroupIdOrderByDisplayOrderAscNameAsc(UUID groupId);
    /** Duplicate pre-check for the master create path (uk_option_group_code). */
    Optional<CustomizationOption> findByGroupIdAndCodeIgnoreCase(UUID groupId, String code);
}

