package com.donotmiss.backend.organization;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Which organisation accounts belong to which organisation.
 *
 * <p><b>Design gap, kept on purpose (D1, agreed 2026-10-26):</b> membership is recorded but not
 * verified. An account publishing under an organisation it does not belong to becomes a member
 * automatically, so anyone can still publish under any organisation name. The policy lives in
 * {@link #ensureCanPublish} only; closing the gap (invitation codes, operator approval, refusing
 * {@code AUTO_JOINED}) is a change to that one method.</p>
 */
@Service
public class OrganizationMembershipService {
    private static final Logger log = LoggerFactory.getLogger(OrganizationMembershipService.class);

    private final OrganizationService organizationService;
    private final OrganizationMemberRepository memberRepository;

    public OrganizationMembershipService(OrganizationService organizationService, OrganizationMemberRepository memberRepository) {
        this.organizationService = organizationService;
        this.memberRepository = memberRepository;
    }

    /** Returns the organisation, recording how the account came to publish under it. */
    @Transactional
    public OrganizationEntity ensureCanPublish(String userId, String organizationName) {
        OrganizationEntity organization = organizationService.ensureOrganization(organizationName);
        if (memberRepository.existsByOrganizationIdAndUserId(organization.getId(), userId)) {
            return organization;
        }
        OrganizationMemberEntity.Source source = memberRepository.existsByOrganizationId(organization.getId())
                ? OrganizationMemberEntity.Source.AUTO_JOINED
                : OrganizationMemberEntity.Source.CLAIMED;
        memberRepository.save(OrganizationMemberEntity.of(organization.getId(), userId, source));
        if (source == OrganizationMemberEntity.Source.AUTO_JOINED) {
            // The case a real check would refuse; logged so it can be found until then.
            log.warn("Account {} auto-joined organisation '{}' that already had members (unverified).",
                    userId, organization.getName());
        }
        return organization;
    }
}
