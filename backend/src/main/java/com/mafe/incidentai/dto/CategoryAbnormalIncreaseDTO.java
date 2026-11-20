package com.mafe.incidentai.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Comparison of incident volume per category between the last 7 complete days and the previous 7 days")
public record CategoryAbnormalIncreaseDTO(
        @Schema(description = "Category ID", example = "1")
        Long categoryId,

        @Schema(description = "Name of the technical category", example = "Networking & Connectivity")
        String categoryName,

        @Schema(description = "Incidents registered in the last 7 complete days", example = "9")
        Long currentPeriodCount,

        @Schema(description = "Incidents registered in the previous 7 days", example = "4")
        Long previousPeriodCount,

        @Schema(description = "Percentage variation between both periods", example = "125.0")
        Double variationPercentage,

        @Schema(description = "True when the percentage increase is greater than or equal to 50%", example = "true")
        Boolean alert
) {}
