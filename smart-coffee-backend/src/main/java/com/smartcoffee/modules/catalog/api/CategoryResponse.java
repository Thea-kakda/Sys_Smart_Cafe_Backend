package com.smartcoffee.modules.catalog.api;
import java.util.UUID;
public record CategoryResponse(UUID categoryId,UUID parentCategoryId,String code,String name,String description,String imageUrl,int displayOrder) {}

