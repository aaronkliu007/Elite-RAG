package com.yunjihai.rag.solution.generation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record GenerationKey(
        String clientKey,
        UUID solutionId,
        UUID requirementVersionId,
        UUID outlineVersionId,
        UUID sectionId,
        Optional<UUID> baselineSectionVersionId) {

    public GenerationKey {
        Objects.requireNonNull(clientKey, "clientKey");
        clientKey = clientKey.strip();
        if (clientKey.isEmpty() || clientKey.length() > 128) {
            throw new IllegalArgumentException("invalid generation client key");
        }
        Objects.requireNonNull(solutionId, "solutionId");
        Objects.requireNonNull(requirementVersionId, "requirementVersionId");
        Objects.requireNonNull(outlineVersionId, "outlineVersionId");
        Objects.requireNonNull(sectionId, "sectionId");
        baselineSectionVersionId = Objects.requireNonNull(
                baselineSectionVersionId, "baselineSectionVersionId");
    }

    public String inputFingerprint() {
        var canonical = String.join("\n",
                solutionId.toString(),
                requirementVersionId.toString(),
                outlineVersionId.toString(),
                sectionId.toString(),
                baselineSectionVersionId.map(UUID::toString).orElse("-"));
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
