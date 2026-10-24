package com.verso.connector.api.enums;

/**
 * How much of the source a run listed (ADR-0044). Only a complete FULL listing deletes what the source no longer has;
 * an INCREMENTAL one sees changes only and never deletes. SMB runs are always FULL.
 */
public enum ListingMode {
    FULL,
    INCREMENTAL
}
