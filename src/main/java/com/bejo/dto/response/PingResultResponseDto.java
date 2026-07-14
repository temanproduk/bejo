package com.bejo.dto.response;

import java.util.List;

/**
 * The outcome of {@code POST /api/presences/pings}. A ping doesn't necessarily reach everyone
 * — a member with no registered device can't be pinged at all, and one pinged within the last
 * hour is still cooling down — so the caller is told how many of the team were actually
 * reached rather than being left to assume it was all of them.
 *
 * @param pinged       how many members were pinged (= {@code presences.size()})
 * @param totalMembers how many members the business has in total
 * @param skipped      the members who were <b>not</b> pinged, each with the reason
 * @param presences    the PING entries created, one per pinged member
 */
public record PingResultResponseDto(
        int pinged,
        int totalMembers,
        List<SkippedMemberDto> skipped,
        List<PresenceResponseDto> presences
) {
    /** @param reason NO_DEVICE (no device registered for push) or COOLDOWN (pinged within the hour) */
    public record SkippedMemberDto(Long userId, String username, String name, String reason) {}
}
