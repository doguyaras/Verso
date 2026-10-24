package com.verso.connector.api.enums;

/** What a connector mirrors (ADR-0038, ADR-0044, ADR-0045). A connector's type is fixed at creation. */
public enum ConnectorType {
    /** A folder of an SMB file share (ADR-0038). */
    SMB,
    /** Spaces of a Confluence Data Center/Server or Cloud site through its REST API (ADR-0044). */
    CONFLUENCE,
    /** Document libraries of a SharePoint Online site or a OneDrive through Microsoft Graph (ADR-0045). */
    SHAREPOINT
}
