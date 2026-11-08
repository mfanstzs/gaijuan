package app.deskgrade.desktop;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.core.domain.model.ProviderProfile;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LocalModelSetupTest {
  @Test
  void unchangedSavedModelKeepsItsVerificationAndDoesNotRequireAnotherCall() {
    var saved = verified(LocalModelDefaults.profiles().getFirst(), true);
    var next = draft(saved, saved.endpoint(), saved.modelId(), false);
    assertSame(saved, next);
    assertTrue(LocalModelSetup.ready(next, LocalModelSetup.Purpose.PRIMARY, true));
  }

  @Test
  void editingModelCreatesUnassignedDraftButCanKeepSameEndpointCachedKey() {
    var saved = verified(LocalModelDefaults.profiles().getFirst(), true);
    var next = draft(saved, saved.endpoint(), "different-model", false);
    assertNotEquals(saved.id(), next.id());
    assertEquals(saved.credentialRef(), next.credentialRef());
    assertFalse(next.isVerified());
    assertTrue(saved.isVerified());
    assertEquals("qwen3.8-flash", saved.modelId());
  }

  @Test
  void replacingAKeyUsesIndependentCacheAndNeverInvalidatesOriginalModel() {
    var saved = verified(LocalModelDefaults.profiles().getFirst(), true);
    var next = draft(saved, saved.endpoint(), saved.modelId(), true);
    assertNotEquals(saved.id(), next.id());
    assertNotEquals(saved.credentialRef(), next.credentialRef());
    assertNull(next.verification());
    assertTrue(saved.isVerified());
  }

  @Test
  void switchingVendorNeverReusesOtherVendorsCredentialReference() {
    var openai = verified(openAiProfile(), false);
    var next =
        draft(openai, URI.create(LocalModelSetup.Vendor.QWEN.endpoint), "qwen3.8-flash", false);
    assertNotEquals(openai.credentialRef(), next.credentialRef());
    assertNotEquals(openai.id(), next.id());
    assertEquals(LocalModelSetup.Vendor.QWEN, LocalModelSetup.vendorOf(next));
    assertEquals(LocalModelSetup.Vendor.OPENAI, LocalModelSetup.vendorOf(openai));
  }

  @Test
  void changingOnlyEndpointStillIsolatesCredentials() {
    var saved = LocalModelDefaults.profiles().getFirst();
    var next =
        draft(
            saved,
            URI.create("https://example.invalid/v1/chat/completions"),
            saved.modelId(),
            false);
    assertNotEquals(saved.credentialRef(), next.credentialRef());
    assertEquals(LocalModelSetup.Vendor.CUSTOM, LocalModelSetup.vendorOf(next));
  }

  @Test
  void doubaoCandidateUsesItsOwnVendorAndCannotInheritQwenParametersOrCredentials() {
    var template = LocalModelDefaults.profiles().getFirst();
    var original =
        verified(
            new ProviderProfile(
                template.id(),
                template.revision(),
                template.displayName(),
                template.endpoint(),
                template.modelId(),
                template.credentialRef(),
                template.structuredMode(),
                Map.of("enable_thinking", "false"),
                Set.of("enable_thinking"),
                template.connectTimeout(),
                template.requestTimeout(),
                template.pricing(),
                null),
            true);
    var next =
        draft(
            original,
            URI.create(LocalModelSetup.Vendor.DOUBAO.endpoint),
            app.deskgrade.adapters.model.DoubaoEndpointCatalog.SEED_2_1_LITE,
            false);
    assertEquals(LocalModelSetup.Vendor.DOUBAO, LocalModelSetup.vendorOf(next));
    assertTrue(LocalModelSetup.modelLabel(next.modelId()).contains("豆包"));
    assertNotEquals(original.credentialRef(), next.credentialRef());
    assertTrue(next.parameters().isEmpty());
    assertTrue(next.allowedParameters().isEmpty());
    assertNull(next.verification());
    assertFalse(QwenThinkingOptions.supported(next.endpoint(), next.modelId()));
  }

  @Test
  void textOnlyVerificationCannotAuthorizeImageGradingAndMissingKeyCannotPass() {
    var text = verified(LocalModelDefaults.profiles().get(2), false);
    assertTrue(LocalModelSetup.ready(text, LocalModelSetup.Purpose.RUBRIC, true));
    assertFalse(LocalModelSetup.ready(text, LocalModelSetup.Purpose.PRIMARY, true));
    assertFalse(LocalModelSetup.ready(text, LocalModelSetup.Purpose.FALLBACK, true));
    assertFalse(LocalModelSetup.ready(text, LocalModelSetup.Purpose.RUBRIC, false));
  }

  @Test
  void freshInstallationDefaultsAllRolesToUnverifiedQwenWithoutChangingOtherVendorSupport() {
    var defaults = LocalModelDefaults.profiles();
    var selection = LocalModelDefaults.selection();
    assertEquals(3, defaults.size());
    assertTrue(
        defaults.stream()
            .allMatch(p -> LocalModelSetup.vendorOf(p) == LocalModelSetup.Vendor.QWEN));
    assertTrue(defaults.stream().noneMatch(ProviderProfile::isStructuredVerified));
    var rubric =
        defaults.stream()
            .filter(p -> p.id().equals(selection.rubricId()))
            .findFirst()
            .orElseThrow();
    assertEquals("qwen3.8-max", rubric.modelId());
    assertEquals(
        "qwen3.8-max",
        LocalModelSetup.defaultModelId(
            LocalModelSetup.Purpose.RUBRIC, LocalModelSetup.Vendor.QWEN));
    assertEquals(LocalModelSetup.Vendor.OPENAI, LocalModelSetup.vendorOf(openAiProfile()));
  }

  @Test
  void switchingFromOpenAiCanSelectVerifiedCachedQwenWithoutCopyingOrRevalidatingItsProfile() {
    var original = verified(openAiProfile(), false);
    var qwen = verified(LocalModelDefaults.profiles().get(1), true);
    var candidate =
        LocalModelSetup.savedVendorProfile(
            List.of(original, qwen),
            LocalModelSetup.Purpose.RUBRIC,
            LocalModelSetup.Vendor.QWEN,
            original.id(),
            p -> p.equals(qwen));
    assertSame(qwen, candidate);
    assertTrue(LocalModelSetup.ready(candidate, LocalModelSetup.Purpose.RUBRIC, true));
    assertNotEquals(original.credentialRef(), candidate.credentialRef());
    assertTrue(original.isStructuredVerified());
    assertEquals(LocalModelSetup.Vendor.OPENAI, LocalModelSetup.vendorOf(original));
  }

  @Test
  void editorCandidateSelectionPrefersReadyProfilesAndKeepsTextOnlyChecksPurposeSpecific() {
    var textOnly = verified(LocalModelDefaults.profiles().get(2), false);
    var vision = verified(LocalModelDefaults.profiles().getFirst(), true);
    var unverifiedMax = LocalModelDefaults.profiles().get(1);
    var candidates = List.of(unverifiedMax, vision, textOnly);
    assertSame(
        textOnly,
        LocalModelSetup.savedVendorProfile(
            candidates,
            LocalModelSetup.Purpose.RUBRIC,
            LocalModelSetup.Vendor.QWEN,
            null,
            p -> true));
    assertSame(
        vision,
        LocalModelSetup.savedVendorProfile(
            candidates,
            LocalModelSetup.Purpose.PRIMARY,
            LocalModelSetup.Vendor.QWEN,
            null,
            p -> true));
    assertSame(
        unverifiedMax,
        LocalModelSetup.savedVendorProfile(
            candidates,
            LocalModelSetup.Purpose.RUBRIC,
            LocalModelSetup.Vendor.QWEN,
            unverifiedMax.id(),
            p -> true));
    assertFalse(LocalModelSetup.ready(unverifiedMax, LocalModelSetup.Purpose.RUBRIC, true));
  }

  @Test
  void editorNeverSelectsOtherEndpointOrTreatsMissingKeyAsReady() {
    var template = LocalModelDefaults.profiles().getFirst();
    var custom =
        draft(
            template,
            URI.create("https://example.invalid/v1/chat/completions"),
            template.modelId(),
            false);
    var original = verified(openAiProfile(), false);
    assertNull(
        LocalModelSetup.savedVendorProfile(
            List.of(custom, original),
            LocalModelSetup.Purpose.RUBRIC,
            LocalModelSetup.Vendor.QWEN,
            null,
            p -> true));
    assertNull(
        LocalModelSetup.savedVendorProfile(
            List.of(custom),
            LocalModelSetup.Purpose.RUBRIC,
            LocalModelSetup.Vendor.CUSTOM,
            custom.id(),
            p -> true));
    var verifiedWithoutKey = verified(LocalModelDefaults.profiles().get(1), true);
    var unverifiedWithKey = LocalModelDefaults.profiles().get(2);
    var selected =
        LocalModelSetup.savedVendorProfile(
            List.of(verifiedWithoutKey, unverifiedWithKey),
            LocalModelSetup.Purpose.RUBRIC,
            LocalModelSetup.Vendor.QWEN,
            null,
            p -> p.equals(unverifiedWithKey));
    assertSame(unverifiedWithKey, selected);
    assertFalse(LocalModelSetup.ready(selected, LocalModelSetup.Purpose.RUBRIC, true));
  }

  @Test
  void changingThinkingModeCreatesAnUnverifiedIsolatedDraftAndPreservesTheOriginal() {
    var saved = verified(LocalModelDefaults.profiles().getFirst(), true);
    var next =
        LocalModelSetup.savedOrIsolatedDraft(
            saved,
            LocalModelSetup.Purpose.PRIMARY,
            saved.displayName(),
            saved.endpoint(),
            saved.modelId(),
            saved.structuredMode(),
            false,
            UUID.randomUUID(),
            QwenThinkingOptions.Mode.LOW);
    assertNotEquals(saved.id(), next.id());
    assertEquals(saved.credentialRef(), next.credentialRef());
    assertEquals(Map.of("enable_thinking", "true", "reasoning_effort", "low"), next.parameters());
    assertNull(next.verification());
    assertFalse(next.isVerified());
    assertTrue(saved.isVerified());
    assertTrue(saved.parameters().isEmpty());
  }

  @Test
  void keepingAnExplicitModePreservesVerificationAndOtherOptionsExactly() {
    var template = LocalModelDefaults.profiles().getFirst();
    var params =
        Map.of(
            "enable_thinking",
            "true",
            "reasoning_effort",
            "low",
            "temperature",
            "0.1",
            "max_tokens",
            "4000");
    var saved =
        verified(
            new ProviderProfile(
                template.id(),
                template.revision(),
                template.displayName(),
                template.endpoint(),
                template.modelId(),
                template.credentialRef(),
                template.structuredMode(),
                params,
                params.keySet(),
                template.connectTimeout(),
                template.requestTimeout(),
                template.pricing(),
                null),
            true);
    var kept = draft(saved, saved.endpoint(), saved.modelId(), false);
    assertSame(saved, kept);
    var sameExplicit =
        LocalModelSetup.savedOrIsolatedDraft(
            saved,
            LocalModelSetup.Purpose.PRIMARY,
            saved.displayName(),
            saved.endpoint(),
            saved.modelId(),
            saved.structuredMode(),
            false,
            UUID.randomUUID(),
            QwenThinkingOptions.Mode.LOW);
    assertSame(saved, sameExplicit);
    var none =
        LocalModelSetup.savedOrIsolatedDraft(
            saved,
            LocalModelSetup.Purpose.PRIMARY,
            saved.displayName(),
            saved.endpoint(),
            saved.modelId(),
            saved.structuredMode(),
            false,
            UUID.randomUUID(),
            QwenThinkingOptions.Mode.NONE);
    assertEquals(
        Map.of("enable_thinking", "false", "temperature", "0.1", "max_tokens", "4000"),
        none.parameters());
    assertFalse(none.allowedParameters().contains("reasoning_effort"));
    assertNull(none.verification());
  }

  @Test
  void changingModelOrEndpointWithKeepDoesNotCarryOldThinkingOptions() {
    var template = LocalModelDefaults.profiles().getFirst();
    var saved =
        verified(
            new ProviderProfile(
                template.id(),
                template.revision(),
                template.displayName(),
                template.endpoint(),
                template.modelId(),
                template.credentialRef(),
                template.structuredMode(),
                Map.of("enable_thinking", "false"),
                Set.of("enable_thinking"),
                template.connectTimeout(),
                template.requestTimeout(),
                template.pricing(),
                null),
            true);
    var otherModel = draft(saved, saved.endpoint(), "qwen3.8-max", false);
    assertTrue(otherModel.parameters().isEmpty());
    var otherEndpoint =
        draft(saved, URI.create(LocalModelSetup.Vendor.OPENAI.endpoint), "gpt-6-luna", false);
    assertTrue(otherEndpoint.parameters().isEmpty());
    assertNotEquals(saved.credentialRef(), otherEndpoint.credentialRef());
  }

  private static ProviderProfile draft(
      ProviderProfile original, URI endpoint, String model, boolean newKey) {
    return LocalModelSetup.savedOrIsolatedDraft(
        original,
        LocalModelSetup.Purpose.PRIMARY,
        original.displayName(),
        endpoint,
        model,
        original.structuredMode(),
        newKey,
        UUID.randomUUID());
  }

  private static ProviderProfile verified(ProviderProfile profile, boolean vision) {
    return profile.withVerification(
        new ProviderProfile.Verification(profile.configDigest(), Instant.EPOCH, vision, true));
  }

  private static ProviderProfile openAiProfile() {
    var template = LocalModelDefaults.profiles().getFirst();
    return new ProviderProfile(
        "legacy_openai_rubric",
        1,
        "Existing OpenAI rule generator",
        URI.create(LocalModelSetup.Vendor.OPENAI.endpoint),
        "gpt-6-luna",
        "legacy_openai_key",
        template.structuredMode(),
        Map.of(),
        Set.of(),
        template.connectTimeout(),
        template.requestTimeout(),
        null,
        null);
  }
}
