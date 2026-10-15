package com.andrewcorp.webwallet.imports.preview;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Removes the import previews that were neither confirmed nor discarded in time. */
@Component
public class ImportPreviewCleanupTask {

    private static final Logger log = LoggerFactory.getLogger(ImportPreviewCleanupTask.class);

    private final ImportPreviewService previews;

    public ImportPreviewCleanupTask(ImportPreviewService previews) {
        this.previews = previews;
    }

    @Scheduled(cron = "0 */15 * * * *")
    public void purge() {
        int removed = previews.purgeExpired();
        if (removed > 0) {
            log.info("Removed {} expired import previews", removed);
        }
    }
}
