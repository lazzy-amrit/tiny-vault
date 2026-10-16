package dev.tinyvault;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class CompatibilityTest {
    @Test
    void keepsTimerAndWebhookConfigurationHelpersCompatible() {
        assertEquals(Duration.ofMinutes(15), BackupCoordinator.parseInterval("15m"));
        assertEquals(Duration.ofHours(4), BackupCoordinator.parseInterval("4h"));
        assertEquals(Duration.ofHours(24), BackupCoordinator.parseInterval("24"));
        assertEquals("https://discord.com/api/webhooks/123/token",
                DiscordWebhook.sanitizeWebhookUrl(" `https://discord.com/api/webhooks/123/token` "));
    }
}