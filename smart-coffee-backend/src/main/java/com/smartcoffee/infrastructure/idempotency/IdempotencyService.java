package com.smartcoffee.infrastructure.idempotency;
import com.smartcoffee.common.exception.ConflictException; import java.time.*; import java.util.UUID; import org.springframework.stereotype.Service; import org.springframework.transaction.annotation.Transactional;
@Service public class IdempotencyService { private final IdempotencyRecordRepository repo; public IdempotencyService(IdempotencyRecordRepository repo){this.repo=repo;}
 @Transactional public ApiIdempotencyRecord claim(String scope,String actorType,UUID actorId,UUID key,String hash,Duration retention){var old=repo.findByScopeAndActorTypeAndActorIdAndIdempotencyKey(scope,actorType,actorId,key);if(old.isPresent()){var r=old.get();if(!r.requestHash().equals(hash))throw new ConflictException("IDEMPOTENCY_KEY_REUSED","Idempotency key already used with a different request");if(r.status()==IdempotencyStatus.PROCESSING)throw new ConflictException("IDEMPOTENCY_REQUEST_IN_PROGRESS","Request is still processing");return r;}return repo.save(ApiIdempotencyRecord.processing(scope,actorType,actorId,key,hash,Instant.now().plus(retention)));}
 // Marks a claim COMPLETED and explicitly re-merges it. Callers that mutate the
 // shared persistence context mid-transaction with clearAutomatically=true native
 // queries (e.g. production completion balance writes) detach the record, so the
 // in-place complete() would never be dirty-checked; the save() re-attaches it.
 @Transactional public void complete(ApiIdempotencyRecord record,int httpStatus,String body){record.complete(httpStatus,body);repo.save(record);}
}

