package com.verso.qa.service;

import com.verso.qa.api.dto.UsageMetricsResponse;
import java.time.LocalDate;

/** GET /v1/admin/metrics (ADR-0023): aggregates only, verso-admin only (checked on the endpoint). */
public interface UsageMetricsService {

    UsageMetricsResponse metrics(LocalDate from, LocalDate to);
}
