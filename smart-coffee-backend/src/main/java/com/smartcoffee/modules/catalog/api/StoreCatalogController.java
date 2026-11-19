package com.smartcoffee.modules.catalog.api;
import com.smartcoffee.common.api.*;
import com.smartcoffee.common.context.CorrelationContext;
import com.smartcoffee.modules.catalog.application.ChannelCatalogService;
import com.smartcoffee.modules.catalog.application.ChannelContextResolver;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;
/**
 * Store catalog read (Slice 3B). POS and the Customer surfaces share this endpoint;
 * the channel is resolved server-side from trusted authentication context
 * ({@link ChannelContextResolver}), NEVER from a caller-supplied {@code ?channel=}
 * parameter (which could leak POS-only products). The response envelope is unchanged
 * and only gains additive availability fields, so existing clients keep working.
 */
@RestController @RequestMapping("/api/v1/stores/{storeId}/catalog")
public class StoreCatalogController {
 private final ChannelCatalogService catalog; private final ChannelContextResolver channels;
 public StoreCatalogController(ChannelCatalogService catalog,ChannelContextResolver channels){this.catalog=catalog;this.channels=channels;}
 @GetMapping public ApiResponse<PaginatedResponse<ProductSummaryResponse>> list(@PathVariable UUID storeId,@RequestParam(defaultValue="")String search,@RequestParam(required=false)UUID categoryId,@RequestParam(defaultValue="false")boolean availableOnly,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size){Page<ProductSummaryResponse> r=catalog.list(storeId,channels.resolve(),search,categoryId,availableOnly,page,size);var m=new PaginatedResponse.Pagination(r.getNumber(),r.getSize(),r.getTotalElements(),r.getTotalPages(),r.isFirst(),r.isLast());return ApiResponse.ok(new PaginatedResponse<>(r.getContent(),m),CorrelationContext.requestId());}
 @GetMapping("/products/{productId}") public ApiResponse<ProductDetailsResponse> details(@PathVariable UUID storeId,@PathVariable UUID productId){return ApiResponse.ok(catalog.detail(storeId,channels.resolve(),productId),CorrelationContext.requestId());}
}
