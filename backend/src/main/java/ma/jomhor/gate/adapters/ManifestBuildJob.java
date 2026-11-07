package ma.jomhor.gate.adapters;

import ma.jomhor.gate.application.ManifestService;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs ManifestService.buildDue on {@code jomhor.gate.manifest-cron} (every 60 s, so v1 follows T0 - lead within a
 * minute; later versions wait 5 min after the previous one; "-" = off, as in tests). Safe on several instances.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class ManifestBuildJob {

    private final ManifestService manifests;

    ManifestBuildJob(ManifestService manifests) {
        this.manifests = manifests;
    }

    @Scheduled(cron = "${jomhor.gate.manifest-cron:0 * * * * *}")
    void build() {
        manifests.buildDue();
    }
}
