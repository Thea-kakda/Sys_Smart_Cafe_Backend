package com.smartcoffee.infrastructure.realtime;
import org.springframework.messaging.simp.SimpMessagingTemplate; import org.springframework.stereotype.Component;
@Component public class WebSocketRealtimePublisher implements RealtimeEventPublisher { private final SimpMessagingTemplate messaging; private final RealtimeDestinationResolver resolver; public WebSocketRealtimePublisher(SimpMessagingTemplate m,RealtimeDestinationResolver r){messaging=m;resolver=r;} public void toUser(String userId,String destination,Object event){messaging.convertAndSendToUser(userId,destination,event);} public void toStore(String storeId,String destination,Object event){messaging.convertAndSend(resolver.store(storeId,destination),event);} }

