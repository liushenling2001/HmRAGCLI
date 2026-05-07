package com.hmrag.backend.service;

import com.hmrag.backend.config.AppProperties;
import com.hmrag.backend.domain.DomainCandidateDiscoveryState;
import com.hmrag.backend.web.dto.ApiDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;

@Component
public class DomainCandidateDiscoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(DomainCandidateDiscoveryScheduler.class);

    private final DomainCandidateService domainCandidateService;
    private final DomainCandidateDiscoveryControlService domainCandidateDiscoveryControlService;
    private final AppProperties appProperties;

    public DomainCandidateDiscoveryScheduler(
            DomainCandidateService domainCandidateService,
            DomainCandidateDiscoveryControlService domainCandidateDiscoveryControlService,
            AppProperties appProperties
    ) {
        this.domainCandidateService = domainCandidateService;
        this.domainCandidateDiscoveryControlService = domainCandidateDiscoveryControlService;
        this.appProperties = appProperties;
    }

    @Scheduled(fixedDelayString = "${hmrag.domain-knowledge.candidate-discovery-poll-delay-millis:600000}")
    public void scheduleCandidateDiscovery() {
        AppProperties.DomainKnowledge settings = appProperties.domainKnowledge();
        if (!domainCandidateDiscoveryControlService.isAutoDiscoveryRunnable()) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MINUTES);
        if (!isWithinWindow(now, settings.candidateDiscoveryWindowStartHour(), settings.candidateDiscoveryWindowEndHour())) {
            return;
        }
        try {
            OffsetDateTime earliestIndexedAt = domainCandidateService.findEarliestIndexedAt();
            if (earliestIndexedAt == null) {
                return;
            }
            DomainCandidateDiscoveryState state = domainCandidateDiscoveryControlService.getOrCreateState("auto");
            if (!passedMinInterval(state, now, settings.candidateDiscoveryMinHoursBetweenRuns())) {
                return;
            }
            int sliceHours = Math.max(1, settings.candidateDiscoverySliceHours());
            OffsetDateTime alignedNow = domainCandidateDiscoveryControlService.alignAutoCursorEnd(now);
            OffsetDateTime windowEnd = state.getCursorWindowEnd() == null ? alignedNow : state.getCursorWindowEnd();
            if (!windowEnd.isAfter(earliestIndexedAt)) {
                state.setCoverageCompletedAt(alignedNow);
                windowEnd = alignedNow;
            }
            OffsetDateTime windowStart = windowEnd.minusHours(sliceHours);
            if (windowStart.isBefore(earliestIndexedAt)) {
                windowStart = earliestIndexedAt;
            }
            var created = domainCandidateService.discover(new ApiDtos.DiscoverDomainCandidatesRequest(
                    settings.candidateDiscoveryLookbackHours(),
                    settings.candidateDiscoveryMaxDocuments(),
                    settings.candidateDiscoveryMaxCandidates(),
                    "auto",
                    windowStart,
                    windowEnd
            ));
            state.setCursorWindowStart(windowStart);
            state.setCursorWindowEnd(windowStart);
            state.setLastRunAt(now);
            if (!windowStart.isAfter(earliestIndexedAt) && !windowStart.isEqual(alignedNow)) {
                state.setCoverageCompletedAt(now);
                state.setCursorWindowEnd(alignedNow);
                state.setCursorWindowStart(alignedNow.minusHours(sliceHours));
            }
            domainCandidateDiscoveryControlService.updateState(state);
            if (!created.isEmpty()) {
                log.info("Auto discovered {} candidate domains for window {} -> {}", created.size(), windowStart, windowEnd);
            }
        } catch (Exception ex) {
            log.error("Failed to auto discover domain candidates", ex);
        }
    }

    private boolean passedMinInterval(DomainCandidateDiscoveryState state, OffsetDateTime now, int minHoursBetweenRuns) {
        OffsetDateTime lastRunAt = state.getLastRunAt();
        return lastRunAt == null
                || lastRunAt.plusHours(Math.max(1, minHoursBetweenRuns)).isBefore(now)
                || lastRunAt.plusHours(Math.max(1, minHoursBetweenRuns)).isEqual(now);
    }

    private boolean isWithinWindow(OffsetDateTime now, int startHour, int endHour) {
        int normalizedStart = normalizeHour(startHour);
        int normalizedEnd = normalizeHour(endHour);
        int currentHour = now.getHour();
        if (normalizedStart == normalizedEnd) {
            return true;
        }
        if (normalizedStart < normalizedEnd) {
            return currentHour >= normalizedStart && currentHour < normalizedEnd;
        }
        return currentHour >= normalizedStart || currentHour < normalizedEnd;
    }

    private int normalizeHour(int hour) {
        if (hour < 0) {
            return 0;
        }
        if (hour > 23) {
            return 23;
        }
        return hour;
    }
}
