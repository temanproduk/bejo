package com.bejo.campaign.repository;

import com.bejo.campaign.domain.AdCampaign;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AdCampaignRepository extends JpaRepository<AdCampaign, Long> {

    Page<AdCampaign> findAllByOrderByIdDesc(Pageable pageable);
}
