package com.bejo.repository;

import com.bejo.entity.Session;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface SessionRepository extends JpaRepository<Session, Long> {

    Optional<Session> findByUsernameAndDeviceId(String username, String deviceId);

    @Modifying
    @Query("UPDATE Session s SET s.pushToken = :pushToken, s.lastUpdated = :now WHERE s.username = :username AND s.deviceId = :deviceId")
    int updatePushToken(@Param("username") String username,
                        @Param("deviceId") String deviceId,
                        @Param("pushToken") String pushToken,
                        @Param("now") LocalDateTime now);
}
