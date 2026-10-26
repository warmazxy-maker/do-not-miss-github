package com.donotmiss.backend.access;

import com.donotmiss.backend.auth.AuthServiceAccess;
import com.donotmiss.backend.common.ApiException;
import com.donotmiss.backend.organization.OrganizationEntity;
import com.donotmiss.backend.organization.OrganizationMemberEntity;
import com.donotmiss.backend.organization.OrganizationMemberRepository;
import com.donotmiss.backend.organization.OrganizationMembershipService;
import com.donotmiss.backend.organization.OrganizationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RegistrationAndMembershipTest {
    @Mock
    private RoleRepository roleRepository;
    @Mock
    private OrganizationService organizationService;
    @Mock
    private OrganizationMemberRepository memberRepository;

    // ---- sign-up may only pick self-registrable roles ----

    @Test
    void signUpDefaultsToStudentAndAcceptsOrganisation() {
        when(roleRepository.findById("STUDENT")).thenReturn(Optional.of(role("STUDENT", true)));
        when(roleRepository.findById("SOCIAL")).thenReturn(Optional.of(role("SOCIAL", true)));

        assertEquals("STUDENT", AuthServiceAccess.registrableRole(roleRepository, null));
        assertEquals("SOCIAL", AuthServiceAccess.registrableRole(roleRepository, "social"));
    }

    @Test
    void signUpCannotPickAPrivilegedOrUnknownRole() {
        when(roleRepository.findById("ADMIN_ZERO")).thenReturn(Optional.of(role("ADMIN_ZERO", false)));
        when(roleRepository.findById("ROOT")).thenReturn(Optional.empty());

        assertThrows(ApiException.class, () -> AuthServiceAccess.registrableRole(roleRepository, "admin_zero"));
        assertThrows(ApiException.class, () -> AuthServiceAccess.registrableRole(roleRepository, "root"));
    }

    // ---- organisation membership (recorded, deliberately not verified yet) ----

    @Test
    void existingMemberPublishesWithoutNewRecord() {
        when(organizationService.ensureOrganization("Kansai Career Tech")).thenReturn(organization(7L));
        when(memberRepository.existsByOrganizationIdAndUserId(7L, "demo-social")).thenReturn(true);

        membership().ensureCanPublish("demo-social", "Kansai Career Tech");

        verify(memberRepository, never()).save(any());
    }

    @Test
    void firstPublisherClaimsTheOrganisationAndLaterOnesAutoJoin() {
        when(organizationService.ensureOrganization("New Lab")).thenReturn(organization(8L));
        when(memberRepository.existsByOrganizationIdAndUserId(any(), any())).thenReturn(false);
        when(memberRepository.existsByOrganizationId(8L)).thenReturn(false, true);

        membership().ensureCanPublish("org-a", "New Lab");
        membership().ensureCanPublish("org-b", "New Lab");

        ArgumentCaptor<OrganizationMemberEntity> saved = ArgumentCaptor.forClass(OrganizationMemberEntity.class);
        verify(memberRepository, org.mockito.Mockito.times(2)).save(saved.capture());
        assertEquals(OrganizationMemberEntity.Source.CLAIMED, saved.getAllValues().get(0).getSource());
        assertEquals(OrganizationMemberEntity.Source.AUTO_JOINED, saved.getAllValues().get(1).getSource());
    }

    private OrganizationMembershipService membership() {
        return new OrganizationMembershipService(organizationService, memberRepository);
    }

    private static RoleEntity role(String code, boolean selfRegistrable) {
        RoleEntity role = new RoleEntity();
        ReflectionTestUtils.setField(role, "code", code);
        ReflectionTestUtils.setField(role, "selfRegistrable", selfRegistrable);
        return role;
    }

    private static OrganizationEntity organization(Long id) {
        OrganizationEntity organization = new OrganizationEntity();
        ReflectionTestUtils.setField(organization, "id", id);
        return organization;
    }
}
