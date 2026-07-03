package com.example;

import com.fasterxml.jackson.annotation.JsonProperty;

public record InvestorRecord(
        @JsonProperty("customer_id") int customer_id,
        @JsonProperty("full_name") String full_name,
        @JsonProperty("annual_income_usd") double annual_income_usd,
        @JsonProperty("customer_segment") String customer_segment,
        @JsonProperty("domicile_currency") String domicile_currency,
        @JsonProperty("join_date") String join_date,
        @JsonProperty("segment_status") String segment_status
) {}
