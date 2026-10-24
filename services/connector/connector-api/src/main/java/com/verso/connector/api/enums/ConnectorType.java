package com.verso.connector.api.enums;

/** What a connector mirrors (ADR-0038, ADR-0044). A connector's type is fixed at creation. */
public enum ConnectorType {
    /** A folder of an SMB file share (ADR-0038). */
    SMB,
    /** Spaces of a Confluence Data Center/Server or Cloud site through its REST API (ADR-0044). */
    CONFLUENCE
}
