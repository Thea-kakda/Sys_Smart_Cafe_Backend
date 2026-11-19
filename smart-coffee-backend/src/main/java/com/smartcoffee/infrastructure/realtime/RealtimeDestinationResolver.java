package com.smartcoffee.infrastructure.realtime;
import org.springframework.stereotype.Component;
@Component public class RealtimeDestinationResolver { public String store(String storeId,String channel){return "/topic/stores/"+storeId+"/"+channel;} }

