package com.bejo.core.dto;

/**
 * Body of {@code POST /api/presences/pings} — the super admin's spot-check. The only field
 * is optional: omit {@code userId} to ping the <b>whole team</b> (its members — never the
 * super admin's own devices), or set it to ping just that one member.
 *
 * <p>There is deliberately no {@code note} here: the note on a PING entry belongs to the
 * <b>member's reply</b> ({@link PresenceRespondRequestDto}), not to the admin who sent it.
 */
public record PresencePingRequestDto(
        // null = every member of the business; set = only that member
        Long userId
) {}
