package com.smartcoffee.infrastructure.cache;
import java.util.UUID; import org.springframework.stereotype.Component;
@Component public class CacheKeyFactory { public String store(UUID id){return "store:"+id;} public String catalog(UUID storeId,String locale){return "catalog:store:"+storeId+":locale:"+locale;} public String permissions(UUID userId,UUID storeId,long securityVersion){return "permissions:user:"+userId+":store:"+storeId+":v:"+securityVersion;} }

