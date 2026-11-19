package com.smartcoffee.modules.production.api;

import jakarta.validation.constraints.Size;

public record ProductionActionRequest(@Size(max = 500) String note) {
}

