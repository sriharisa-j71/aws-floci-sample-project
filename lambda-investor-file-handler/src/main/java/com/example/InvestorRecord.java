package com.example;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonProperty;

public class InvestorRecord {

    private final int customer_id;
    private final String full_name;
    private final double annual_income_usd;
    private final String customer_segment;
    private final String domicile_currency;
    private final String join_date;
    private final String segment_status;

    @JsonCreator
    public InvestorRecord(
            @JsonProperty("customer_id") int customer_id,
            @JsonProperty("full_name") String full_name,
            @JsonProperty("annual_income_usd") double annual_income_usd,
            @JsonProperty("customer_segment") String customer_segment,
            @JsonProperty("domicile_currency") String domicile_currency,
            @JsonProperty("join_date") String join_date,
            @JsonProperty("segment_status") String segment_status
    ) {
        this.customer_id = customer_id;
        this.full_name = full_name;
        this.annual_income_usd = annual_income_usd;
        this.customer_segment = customer_segment;
        this.domicile_currency = domicile_currency;
        this.join_date = join_date;
        this.segment_status = segment_status;
    }

    @JsonGetter("customer_id")
    public int customer_id() { return customer_id; }
    @JsonGetter("full_name")
    public String full_name() { return full_name; }
    @JsonGetter("annual_income_usd")
    public double annual_income_usd() { return annual_income_usd; }
    @JsonGetter("customer_segment")
    public String customer_segment() { return customer_segment; }
    @JsonGetter("domicile_currency")
    public String domicile_currency() { return domicile_currency; }
    @JsonGetter("join_date")
    public String join_date() { return join_date; }
    @JsonGetter("segment_status")
    public String segment_status() { return segment_status; }
}
