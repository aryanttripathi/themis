package io.themis.model;

/** Licenses grouped by the obligations they place on the user, from least to most restrictive. */
public enum LicenseCategory {
    /** Apache-2.0, MIT, BSD: keep notices, otherwise free to use. */
    PERMISSIVE,
    /** LGPL, MPL, EPL: changes to the library itself must be shared. */
    WEAK_COPYLEFT,
    /** GPL: distributing your application can require releasing its source. */
    STRONG_COPYLEFT,
    /** AGPL, SSPL: offering the software over a network can require releasing source. */
    NETWORK_COPYLEFT,
    /** Commercial or custom terms; needs manual review. */
    PROPRIETARY,
    /** No license found or not recognized: legally "all rights reserved" until proven otherwise. */
    UNKNOWN
}
