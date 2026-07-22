package com.bejo.core.domain;

/**
 * A user's role within its business (flat model).
 *
 * <ul>
 *   <li>{@link #SUPER_ADMIN} — the original creator of the business. Immutable: cannot be
 *       demoted to a member, and cannot be deleted. Only super admins may add, update, or
 *       delete other users of the business.</li>
 *   <li>{@link #MEMBER} — an additional user added to the business by a super admin.</li>
 * </ul>
 */
public final class BusinessRole {

    public static final String SUPER_ADMIN = "SUPER_ADMIN";
    public static final String MEMBER = "MEMBER";

    private BusinessRole() {}
}
