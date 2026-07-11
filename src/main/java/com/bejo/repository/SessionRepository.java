package com.bejo.repository;

import com.bejo.entity.Session;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface SessionRepository extends JpaRepository<Session, Long> {

    Optional<Session> findByUsernameAndDeviceId(String username, String deviceId);

    // Remove all of a (soft-deleted) user's device sessions so a reused username starts clean
    void deleteByUsername(String username);

    List<Session> findByUsername(String username);

    // All sessions across a set of users — used to fan a business-wide push out to every
    // member's devices
    List<Session> findByUsernameIn(Collection<String> usernames);

    @Modifying
    @Query("UPDATE Session s SET s.pushToken = :pushToken, s.lastUpdated = :now WHERE s.username = :username AND s.deviceId = :deviceId")
    int updatePushToken(@Param("username") String username,
                        @Param("deviceId") String deviceId,
                        @Param("pushToken") String pushToken,
                        @Param("now") LocalDateTime now);

    // Logout a device: drop its FCM token and mark the session logged out
    @Modifying
    @Query("UPDATE Session s SET s.pushToken = null, s.loginStatus = false, s.lastUpdated = :now WHERE s.username = :username AND s.deviceId = :deviceId")
    int clearPushTokenAndLogout(@Param("username") String username,
                                @Param("deviceId") String deviceId,
                                @Param("now") LocalDateTime now);
}
