package ma.jomhor.crm.adapters;

import ma.jomhor.crm.application.CampaignService;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Runs CampaignService.runDue on {@code jomhor.crm.campaign-cron} (every 30 s; "-" = off, tests call it). */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class CampaignJob {

    private final CampaignService campaigns;

    CampaignJob(CampaignService campaigns) {
        this.campaigns = campaigns;
    }

    @Scheduled(cron = "${jomhor.crm.campaign-cron:*/30 * * * * *}")
    void run() {
        campaigns.runDue();
    }
}
