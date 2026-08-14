package com.scanops.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * 알림 메일 발송.
 *
 * <p>SMTP 미설정(로컬 개발 등, {@code MAIL_HOST} 비어있음)이어도 부팅은 항상 성공한다
 * — {@code spring.mail.host} 프로퍼티 키 자체는 항상 존재해 {@code JavaMailSender} 빈은
 * 생성되지만, 실제 발송은 여기서 건너뛰고 로그만 남긴다. 발송 중 예외도 삼켜서
 * 배치(스케줄러)가 다른 대상 처리를 계속할 수 있게 한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MailService {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.host:}")
    private String mailHost;

    @Value("${app.mail.from:ScanOps <noreply@scanops.dev>}")
    private String from;

    public boolean isConfigured() {
        return mailHost != null && !mailHost.isBlank();
    }

    /** @return 발송을 시도해서 성공했으면 true. 미설정·실패 시 false(예외를 올리지 않는다). */
    public boolean send(String to, String subject, String body) {
        if (to == null || to.isBlank()) {
            log.warn("[메일] 수신 주소 없음 — 발송 생략: subject={}", subject);
            return false;
        }
        if (!isConfigured()) {
            log.info("[메일] SMTP 미설정 — 발송 생략: to={} subject={}", to, subject);
            return false;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(to);
            message.setSubject(subject);
            message.setText(body);
            mailSender.send(message);
            log.info("[메일] 발송 완료: to={} subject={}", to, subject);
            return true;
        } catch (Exception e) {
            log.warn("[메일] 발송 실패: to={} subject={} ({})", to, subject, e.getMessage());
            return false;
        }
    }
}
