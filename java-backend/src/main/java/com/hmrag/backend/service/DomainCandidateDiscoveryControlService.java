package com.hmrag.backend.service;

import com.hmrag.backend.config.AppProperties;
import com.hmrag.backend.domain.DomainCandidateDiscoveryState;
import com.hmrag.backend.repository.DomainCandidateDiscoveryStateRepository;
import com.hmrag.backend.web.dto.ApiDtos;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class DomainCandidateDiscoveryControlService {

    private final AppProperties appProperties;
    private final DomainCandidateDiscoveryStateRepository domainCandidateDiscoveryStateRepository;
    private final AtomicBoolean pausedByUser = new AtomicBoolean(false);

    public DomainCandidateDiscoveryControlService(
            AppProperties appProperties,
            DomainCandidateDiscoveryStateRepository domainCandidateDiscoveryStateRepository
    ) {
        this.appProperties = appProperties;
        this.domainCandidateDiscoveryStateRepository = domainCandidateDiscoveryStateRepository;
    }

    public ApiDtos.DomainCandidateDiscoveryControlItem getStatus() {
        AppProperties.DomainKnowledge settings = appProperties.domainKnowledge();
        boolean configEnabled = settings.candidateDiscoveryEnabled();
        boolean paused = pausedByUser.get();
        DomainCandidateDiscoveryState state = getOrCreateState("auto");
        return new ApiDtos.DomainCandidateDiscoveryControlItem(
                configEnabled,
                configEnabled && !paused,
                isWithinWindow(OffsetDateTime.now(), settings.candidateDiscoveryWindowStartHour(), settings.candidateDiscoveryWindowEndHour()),
                paused,
                settings.candidateDiscoveryWindowStartHour(),
                settings.candidateDiscoveryWindowEndHour(),
                settings.candidateDiscoveryLookbackHours(),
                settings.candidateDiscoveryMaxDocuments(),
                settings.candidateDiscoveryMaxCandidates(),
                settings.candidateDiscoveryMinDocuments(),
                settings.candidateDiscoveryMinHoursBetweenRuns(),
                state.getLastRunAt(),
                state.getCursorWindowStart(),
                state.getCursorWindowEnd(),
                state.getCoverageCompletedAt()
        );
    }

    public ApiDtos.DomainCandidateDiscoveryControlItem start() {
        pausedByUser.set(false);
        return getStatus();
    }

    public ApiDtos.DomainCandidateDiscoveryControlItem stop() {
        pausedByUser.set(true);
        return getStatus();
    }

    public boolean isAutoDiscoveryRunnable() {
        return appProperties.domainKnowledge().candidateDiscoveryEnabled() && !pausedByUser.get();
    }

    public DomainCandidateDiscoveryState getOrCreateState(String triggerSource) {
        return domainCandidateDiscoveryStateRepository.findById(triggerSource)
                .orElseGet(() -> {
                    DomainCandidateDiscoveryState state = new DomainCandidateDiscoveryState();
                    state.setTriggerSource(triggerSource);
                    return domainCandidateDiscoveryStateRepository.save(state);
                });
    }

    public void updateState(DomainCandidateDiscoveryState state) {
        domainCandidateDiscoveryStateRepository.save(state);
    }

    public OffsetDateTime alignAutoCursorEnd(OffsetDateTime time) {
        return time.truncatedTo(ChronoUnit.HOURS);
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
