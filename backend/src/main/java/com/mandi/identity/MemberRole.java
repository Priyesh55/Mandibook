package com.mandi.identity;

/**
 * Business role of a membership. Owner-ness belongs to the membership of one firm, not to the person. The platform
 * administrator is deliberately NOT a value here: it is a separate authority in a separate schema (blueprint 3).
 */
public enum MemberRole {
    OWNER, EMPLOYEE
}
