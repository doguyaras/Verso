package com.verso.document.api.enums;

/**
 * The closed entity types of the light system model (ADR-0054). Deliberately no PERSON: a person's name is never an
 * entity (KVKK, works councils); an owner is a role or a department only.
 */
public enum SystemEntityType {
    /** An application or module: WMS, ERP, "Depo Yönetim Sistemi". */
    SYSTEM,
    /** A screen or a menu path: "Stok > Sayım > Sayım Girişi", "Sipariş Giriş ekranı". */
    SCREEN,
    /** A status code, error code or document type in the AnchorCheck code shape: WMS-204, SAT-E058, P1. */
    CODE,
    /** A job title or a department, never a person: "Depo Sorumlusu", "Satın Alma Departmanı". */
    ROLE,
    /** A heading followed by numbered steps. */
    PROCESS,
    /** One numbered step of a process. */
    STEP,
    /** An abbreviation defined in the text: "FEFO (First Expired First Out)". */
    TERM
}
