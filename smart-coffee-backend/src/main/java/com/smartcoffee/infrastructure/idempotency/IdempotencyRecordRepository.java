package com.smartcoffee.infrastructure.idempotency;
import java.util.*; import org.springframework.data.jpa.repository.JpaRepository;
public interface IdempotencyRecordRepository extends JpaRepository<ApiIdempotencyRecord,UUID> { Optional<ApiIdempotencyRecord> findByScopeAndActorTypeAndActorIdAndIdempotencyKey(String scope,String actorType,UUID actorId,UUID key); }

