package com.smartcoffee.infrastructure.realtime;
public interface RealtimeEventPublisher { void toUser(String userId,String destination,Object event); void toStore(String storeId,String destination,Object event); }

