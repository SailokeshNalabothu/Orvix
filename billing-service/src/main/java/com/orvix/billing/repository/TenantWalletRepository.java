package com.orvix.billing.repository;

import com.orvix.billing.model.TenantWallet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TenantWalletRepository extends JpaRepository<TenantWallet, String> {
}
