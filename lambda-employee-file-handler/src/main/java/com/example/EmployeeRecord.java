package com.example;

import com.fasterxml.jackson.annotation.JsonProperty;

public record EmployeeRecord(
        @JsonProperty("emp_id") int emp_id,
        @JsonProperty("emp_name") String emp_name,
        @JsonProperty("department") String department,
        @JsonProperty("salary") double salary,
        @JsonProperty("hire_date") String hire_date
) {}
