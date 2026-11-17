package cn.siyue.core;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owns financial read transactions and resolves current identity before scoped ledger queries. */
@Service
public class FinanceWorkspaceService {
    private final AccessRepository access;
    private final FinanceWorkspaceRepository repository;

    /**
     * @param access current OIDC identity and permission mappings
     * @param repository scoped financial read projections
     */
    public FinanceWorkspaceService(AccessRepository access, FinanceWorkspaceRepository repository) {
        this.access = access;
        this.repository = repository;
    }

    /**
     * @param issuer validated JWT issuer
     * @param subject validated JWT subject
     * @param filter bounded pagination and optional order/date criteria
     * @return arrears within the current actor's order scope
     * @throws BusinessException if identity, permission or filter validation fails
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public FinanceWorkspaceRepository.Page<FinanceWorkspaceRepository.Arrear> arrears(
            String issuer, String subject, FinanceWorkspaceRepository.Filter filter) {
        return repository.arrears(access.resolve(issuer, subject), filter);
    }

    /**
     * @param issuer validated JWT issuer
     * @param subject validated JWT subject
     * @param filter bounded pagination and optional order/date criteria
     * @return scoped refunds with reason visibility enforced by current grants
     * @throws BusinessException if identity, permission or filter validation fails
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public FinanceWorkspaceRepository.Page<FinanceWorkspaceRepository.Refund> refunds(
            String issuer, String subject, FinanceWorkspaceRepository.Filter filter) {
        return repository.refunds(access.resolve(issuer, subject), filter);
    }

    /**
     * @param issuer validated JWT issuer
     * @param subject validated JWT subject
     * @param filter bounded pagination and optional order/date criteria
     * @return scoped immutable consumption facts
     * @throws BusinessException if identity, permission or filter validation fails
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public FinanceWorkspaceRepository.Page<FinanceWorkspaceRepository.Consumption> consumptions(
            String issuer, String subject, FinanceWorkspaceRepository.Filter filter) {
        return repository.consumptions(access.resolve(issuer, subject), filter);
    }
}
