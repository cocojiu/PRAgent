package com.repoguard.agent.preflight;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.mapper.PreflightPreviewMapper;
import com.repoguard.agent.security.PreflightCredentialService.Grant;
import com.repoguard.agent.security.SecretCryptoService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Committed admission precedes every model call; uncertain outcomes are never retried. */
@Service
public class PreflightPreviewStore {
    private final PreflightPreviewMapper mapper;
    private final SecretCryptoService crypto;

    public PreflightPreviewStore(PreflightPreviewMapper mapper, SecretCryptoService crypto) {
        this.mapper = mapper; this.crypto = crypto;
    }

    @Transactional
    public String claim(Grant grant, String sha, boolean llm) {
        if (mapper.lockTenant(grant.tenantId()) == null)
            throw new BusinessException(ErrorCode.FORBIDDEN, "Inactive preflight tenant");
        mapper.purgeResponses(grant.tenantId());
        mapper.purgeExpired(grant.tenantId());
        if (mapper.exists(grant.tenantId(), grant.userId(), sha) > 0) {
            String cached = mapper.result(grant.tenantId(), grant.userId(), sha);
            if (cached != null) return crypto.decrypt(cached);
            throw new BusinessException(ErrorCode.CONFLICT, "Preview already claimed or summary expired");
        }
        if (mapper.countRecent(grant.tenantId(), false) >= 20
            || (llm && mapper.countRecent(grant.tenantId(), true) >= 5))
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS, "Preflight daily admission budget exhausted");
        try {
            if (mapper.claim(grant.tenantId(), grant.userId(), sha, grant.nonce(), grant.repository(), llm) != 1)
                throw new IllegalStateException("Preflight claim write failed");
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "Preflight credential already used");
        }
        return null;
    }

    public void complete(Grant grant, String sha, String result) {
        if (mapper.complete(grant.tenantId(), grant.userId(), sha, crypto.encrypt(result)) != 1)
            throw new IllegalStateException("Preflight completion fence lost");
    }

    public void cleanupExpired() {
        com.repoguard.agent.tenancy.ScheduledJobLeaseContext.assertHeld();
        mapper.purgeGlobalResponses();
        com.repoguard.agent.tenancy.ScheduledJobLeaseContext.assertHeld();
        mapper.purgeGlobalExpired();
    }

    public void fail(Grant grant, String sha) {
        mapper.fail(grant.tenantId(), grant.userId(), sha);
    }
}
