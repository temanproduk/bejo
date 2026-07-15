package com.bejo.service;

import com.bejo.constant.BusinessRole;
import com.bejo.dto.request.AddMemberRequestDto;
import com.bejo.entity.User;
import com.bejo.exception.ForbiddenException;
import com.bejo.repository.SessionRepository;
import com.bejo.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemberServiceTest {

    private static final Long BIZ = 1L;

    @Mock private UserRepository userRepository;
    @Mock private SessionRepository sessionRepository;
    @Mock private BusinessContext businessContext;
    @Mock private PasswordEncoder passwordEncoder;

    private MemberService service;

    @BeforeEach
    void setUp() {
        service = new MemberService(userRepository, sessionRepository, businessContext, passwordEncoder);
        ReflectionTestUtils.setField(service, "maxUsersPerBusiness", 5);
    }

    private User user(String username, String role) {
        User u = new User();
        u.setUsername(username);
        u.setBusinessId(BIZ);
        u.setBusinessRole(role);
        return u;
    }

    private AddMemberRequestDto addReq(String username) {
        return new AddMemberRequestDto(username, "password1", "New Member",
                username + "@example.com", "+628123456789");
    }

    @Test
    void superAdminAddsMember() {
        when(businessContext.requireUser("admin")).thenReturn(user("admin", BusinessRole.SUPER_ADMIN));
        when(userRepository.countByBusinessIdAndDeletedAtIsNull(BIZ)).thenReturn(2L);
        when(userRepository.existsByUsernameAndDeletedAtIsNull("bob")).thenReturn(false);
        when(passwordEncoder.encode("password1")).thenReturn("$enc");

        service.add("admin", addReq("bob"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getBusinessRole()).isEqualTo(BusinessRole.MEMBER);
        assertThat(captor.getValue().getBusinessId()).isEqualTo(BIZ);
        assertThat(captor.getValue().getPasswordHash()).isEqualTo("$enc");
    }

    @Test
    void nonSuperAdminCannotAdd() {
        when(businessContext.requireUser("bob")).thenReturn(user("bob", BusinessRole.MEMBER));

        assertThatThrownBy(() -> service.add("bob", addReq("carol")))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("super admin");
        verify(userRepository, never()).save(any());
    }

    @Test
    void addRejectedWhenBusinessAtMaxUsers() {
        when(businessContext.requireUser("admin")).thenReturn(user("admin", BusinessRole.SUPER_ADMIN));
        when(userRepository.countByBusinessIdAndDeletedAtIsNull(BIZ)).thenReturn(5L); // already at the cap of 5

        assertThatThrownBy(() -> service.add("admin", addReq("sixth")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batas maksimal");
        verify(userRepository, never()).save(any());
    }

    @Test
    void addRejectsDuplicateUsername() {
        when(businessContext.requireUser("admin")).thenReturn(user("admin", BusinessRole.SUPER_ADMIN));
        when(userRepository.countByBusinessIdAndDeletedAtIsNull(BIZ)).thenReturn(1L);
        when(userRepository.existsByUsernameAndDeletedAtIsNull("bob")).thenReturn(true);

        assertThatThrownBy(() -> service.add("admin", addReq("bob")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Username sudah digunakan");
        verify(userRepository, never()).save(any());
    }

    @Test
    void updateMemberAppliesProvidedFields() {
        User admin = user("admin", BusinessRole.SUPER_ADMIN);
        User member = user("bob", BusinessRole.MEMBER);
        member.setName("Bob");
        member.setPasswordHash("$old");
        when(businessContext.requireUser("admin")).thenReturn(admin);
        when(userRepository.findByIdAndBusinessIdAndDeletedAtIsNull(9L, BIZ)).thenReturn(Optional.of(member));
        when(passwordEncoder.encode("NewSecret1")).thenReturn("$new");

        service.update("admin", 9L, new com.bejo.dto.request.UpdateMemberRequestDto(
                "Bob Updated", null, null, "NewSecret1"));

        assertThat(member.getName()).isEqualTo("Bob Updated");
        assertThat(member.getPasswordHash()).isEqualTo("$new");
        verify(userRepository).save(member);
    }

    @Test
    void updateEmptyRequestRejected() {
        // isEmpty() is checked before the super-admin lookup, so no caller stub is needed
        assertThatThrownBy(() -> service.update("admin", 9L,
                new com.bejo.dto.request.UpdateMemberRequestDto(null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Tidak ada data");
        verify(userRepository, never()).save(any());
    }

    @Test
    void updateSuperAdminIsForbidden() {
        User admin = user("admin", BusinessRole.SUPER_ADMIN);
        when(businessContext.requireUser("admin")).thenReturn(admin);
        when(userRepository.findByIdAndBusinessIdAndDeletedAtIsNull(1L, BIZ)).thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> service.update("admin", 1L,
                new com.bejo.dto.request.UpdateMemberRequestDto("Hacked", null, null, null)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Super admin tidak dapat diubah");
        verify(userRepository, never()).save(any());
    }

    @Test
    void nonSuperAdminCannotUpdate() {
        when(businessContext.requireUser("bob")).thenReturn(user("bob", BusinessRole.MEMBER));

        assertThatThrownBy(() -> service.update("bob", 9L,
                new com.bejo.dto.request.UpdateMemberRequestDto("X", null, null, null)))
                .isInstanceOf(ForbiddenException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void deleteMemberSoftDeletesAndClearsSessions() {
        User admin = user("admin", BusinessRole.SUPER_ADMIN);
        User member = user("bob", BusinessRole.MEMBER);
        when(businessContext.requireUser("admin")).thenReturn(admin);
        when(userRepository.findByIdAndBusinessIdAndDeletedAtIsNull(9L, BIZ)).thenReturn(Optional.of(member));

        service.delete("admin", 9L);

        assertThat(member.getDeletedAt()).isNotNull();
        verify(userRepository).save(member);
        verify(sessionRepository).deleteByUsername("bob");
    }

    @Test
    void deleteSuperAdminIsForbidden() {
        User admin = user("admin", BusinessRole.SUPER_ADMIN);
        when(businessContext.requireUser("admin")).thenReturn(admin);
        when(userRepository.findByIdAndBusinessIdAndDeletedAtIsNull(1L, BIZ)).thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> service.delete("admin", 1L))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Super admin tidak dapat dihapus");
        verify(userRepository, never()).save(any());
        verify(sessionRepository, never()).deleteByUsername(any());
    }
}
