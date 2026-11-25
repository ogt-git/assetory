package com.project.assetory.user.service;

import com.project.assetory.user.domain.UserSettings;
import com.project.assetory.user.repository.UserSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * user_settings 조회 (docs/05 §3.2). 행이 없으면 기본값으로 만든다 — 가입 흐름(이식 모듈 AuthService)을 바꾸지 않는다.
 * 동시에 처음 조회해도 업서트라 1행만 생기고 예외 · rollback-only가 없다. 호출자 트랜잭션에 그대로 참여한다.
 */
@Service
@RequiredArgsConstructor
public class UserSettingsService {

    private final UserSettingsRepository userSettingsRepository;

    @Transactional
    public UserSettings getOrCreate(Long userId) {
        return userSettingsRepository.findById(userId).orElseGet(() -> {
            userSettingsRepository.insertDefaultIfAbsent(userId, UserSettings.DEFAULT_TIMEZONE, LocalDateTime.now());
            return userSettingsRepository.findById(userId).orElseThrow();
        });
    }
}
