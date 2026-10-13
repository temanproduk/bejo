package com.bejo.admin.repository;

import com.bejo.admin.dto.AdminUserRowDto;
import com.bejo.core.domain.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * The dashboard's cross-business user list. Read-only, and deliberately NOT scoped by business — that
 * is what makes it an admin query — so it lives here rather than on core's {@code UserRepository},
 * where every method is business-scoped.
 *
 * <p>No parameter is ever null (an absent filter is {@code ''} / {@code '%'}): Postgres cannot infer
 * the type of a bare {@code :param is null}.
 */
public interface AdminUserQueryRepository extends Repository<User, Long> {

    String FILTER = " from User u join Business b on b.id = u.businessId"
            + " where (lower(u.name) like :q escape '!' or lower(u.username) like :q escape '!'"
            + "   or lower(u.email) like :q escape '!' or u.phone like :q escape '!'"
            + "   or lower(b.name) like :q escape '!')"
            + " and (:role = '' or u.businessRole = :role)"
            + " and (:status = '' or (:status = 'active' and u.deletedAt is null)"
            + "   or (:status = 'deleted' and u.deletedAt is not null))";

    @Query(value = "select new com.bejo.admin.dto.AdminUserRowDto(u.id, u.username, u.name, u.email, u.phone,"
            + " u.businessId, b.name, u.businessRole, u.accountType, u.isEmailVerified, u.createdAt, u.deletedAt)"
            + FILTER + " order by u.createdAt desc, u.id desc",
            countQuery = "select count(u)" + FILTER)
    Page<AdminUserRowDto> search(@Param("q") String likePattern, @Param("role") String role,
                                 @Param("status") String status, Pageable pageable);
}
