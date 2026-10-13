package com.bejo.admin.service;

import com.bejo.admin.dto.AdminTransactionRowDto;
import com.bejo.admin.dto.AdminUserRowDto;
import com.bejo.admin.repository.AdminTransactionQueryRepository;
import com.bejo.admin.repository.AdminUserQueryRepository;
import com.bejo.shared.util.Pagination;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Set;

/** The dashboard's read-only, cross-business lists of users and transactions. */
@Service
public class AdminDirectoryService {

    private static final Set<String> USER_STATUSES = Set.of("", "active", "deleted");

    // Stand-ins for "no bound": the queries take no null parameters
    private static final LocalDateTime EARLIEST = LocalDateTime.of(1970, 1, 1, 0, 0);
    private static final LocalDateTime LATEST = LocalDateTime.of(9999, 1, 1, 0, 0);

    private final AdminUserQueryRepository users;
    private final AdminTransactionQueryRepository transactions;

    public AdminDirectoryService(AdminUserQueryRepository users, AdminTransactionQueryRepository transactions) {
        this.users = users;
        this.transactions = transactions;
    }

    @Transactional(readOnly = true)
    public Page<AdminUserRowDto> users(String q, String role, String status, Integer page, Integer size) {
        String wantedStatus = clean(status).toLowerCase(Locale.ROOT);
        if (!USER_STATUSES.contains(wantedStatus)) {
            throw new IllegalArgumentException("status must be 'active' or 'deleted'");
        }
        return users.search(likePattern(q), clean(role).toUpperCase(Locale.ROOT), wantedStatus,
                Pagination.of(page, size));
    }

    /** {@code dateStart}/{@code dateEnd} are inclusive calendar days (WIB), like the app's history. */
    @Transactional(readOnly = true)
    public Page<AdminTransactionRowDto> transactions(String q, LocalDate dateStart, LocalDate dateEnd,
                                                     String type, String status, Integer page, Integer size) {
        if (dateStart != null && dateEnd != null && dateEnd.isBefore(dateStart)) {
            throw new IllegalArgumentException("dateEnd must not be before dateStart");
        }
        return transactions.search(likePattern(q),
                dateStart == null ? EARLIEST : dateStart.atStartOfDay(),
                dateEnd == null ? LATEST : dateEnd.plusDays(1).atStartOfDay(),
                clean(type).toUpperCase(Locale.ROOT), clean(status).toUpperCase(Locale.ROOT),
                Pagination.of(page, size));
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    // A contains-match with the user's own % and _ taken literally ('!' is the queries' escape character)
    private static String likePattern(String q) {
        String term = clean(q).toLowerCase(Locale.ROOT);
        if (term.isEmpty()) {
            return "%";
        }
        return "%" + term.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }
}
