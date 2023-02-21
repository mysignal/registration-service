/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration;

import com.google.common.annotations.VisibleForTesting;
import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import com.google.protobuf.ByteString;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.apache.commons.lang3.StringUtils;
import org.signal.registration.ratelimit.RateLimiter;
import org.signal.registration.rpc.RegistrationSessionMetadata;
import org.signal.registration.sender.ClientType;
import org.signal.registration.sender.MessageTransport;
import org.signal.registration.sender.SenderException;
import org.signal.registration.sender.SenderSelectionStrategy;
import org.signal.registration.sender.VerificationCodeSender;
import org.signal.registration.session.RegistrationAttempt;
import org.signal.registration.session.RegistrationSession;
import org.signal.registration.session.SessionRepository;
import org.signal.registration.util.CompletionExceptions;
import org.signal.registration.util.UUIDUtil;

/**
 * The registration service is the core orchestrator of registration business logic and manages registration sessions
 * and verification code sender selection.
 */
@Singleton
public class RegistrationService {

  private final SenderSelectionStrategy senderSelectionStrategy;
  private final SessionRepository sessionRepository;
  private final RateLimiter<Phonenumber.PhoneNumber> sessionCreationRateLimiter;
  private final RateLimiter<RegistrationSession> sendSmsVerificationCodeRateLimiter;
  private final RateLimiter<RegistrationSession> sendVoiceVerificationCodeRateLimiter;
  private final RateLimiter<RegistrationSession> checkVerificationCodeRateLimiter;
  private final Clock clock;

  private final Map<String, VerificationCodeSender> sendersByName;

  @VisibleForTesting
  static final Duration SESSION_TTL_AFTER_LAST_ACTION = Duration.ofMinutes(10);

  private record SenderAndSessionData(VerificationCodeSender sender, byte[] sessionData) {}

  @VisibleForTesting
  record NextActionDurations(Optional<Duration> nextSms,
      Optional<Duration> nextVoiceCall,
      Optional<Duration> nextCodeCheck) {}

  /**
   * Constructs a new registration service that chooses verification code senders with the given strategy and stores
   * session data with the given session repository.
   *
   * @param senderSelectionStrategy              the strategy to use to choose verification code senders
   * @param sessionRepository                    the repository to use to store session data
   * @param sessionCreationRateLimiter           a rate limiter that controls the rate at which sessions may be created
   *                                             for individual phone numbers
   * @param sendSmsVerificationCodeRateLimiter   a rate limiter that controls the rate at which callers may request
   *                                             verification codes via SMS for a given session
   * @param sendVoiceVerificationCodeRateLimiter a rate limiter that controls the rate at which callers may request
   * @param checkVerificationCodeRateLimiter     a rate limiter that controls the rate and number of times a caller may
   *                                             check a verification code for a given session
   * @param verificationCodeSenders              a list of verification code senders that may be used by this service
   * @param clock                                the time source for this registration service
   */
  public RegistrationService(final SenderSelectionStrategy senderSelectionStrategy,
      final SessionRepository sessionRepository,
      @Named("session-creation") final RateLimiter<Phonenumber.PhoneNumber> sessionCreationRateLimiter,
      @Named("send-sms-verification-code") final RateLimiter<RegistrationSession> sendSmsVerificationCodeRateLimiter,
      @Named("send-voice-verification-code") final RateLimiter<RegistrationSession> sendVoiceVerificationCodeRateLimiter,
      @Named("check-verification-code") final RateLimiter<RegistrationSession> checkVerificationCodeRateLimiter,
      final List<VerificationCodeSender> verificationCodeSenders,
      final Clock clock) {

    this.senderSelectionStrategy = senderSelectionStrategy;
    this.sessionRepository = sessionRepository;
    this.sessionCreationRateLimiter = sessionCreationRateLimiter;
    this.sendSmsVerificationCodeRateLimiter = sendSmsVerificationCodeRateLimiter;
    this.sendVoiceVerificationCodeRateLimiter = sendVoiceVerificationCodeRateLimiter;
    this.checkVerificationCodeRateLimiter = checkVerificationCodeRateLimiter;
    this.clock = clock;

    this.sendersByName = verificationCodeSenders.stream()
        .collect(Collectors.toMap(VerificationCodeSender::getName, Function.identity()));
  }

  /**
   * Creates a new registration session for the given phone number.
   *
   * @param phoneNumber the phone number for which to create a new registration session
   *
   * @return a future that yields the newly-created registration session once the session has been created and stored in
   * this service's session repository; the returned future may fail with a
   * {@link org.signal.registration.ratelimit.RateLimitExceededException}
   */
  public CompletableFuture<RegistrationSession> createRegistrationSession(final Phonenumber.PhoneNumber phoneNumber) {
    return sessionCreationRateLimiter.checkRateLimit(phoneNumber)
        .thenCompose(ignored ->
            sessionRepository.createSession(phoneNumber, clock.instant().plus(SESSION_TTL_AFTER_LAST_ACTION)));
  }

  /**
   * Retrieves a registration session by its unique identifier.
   *
   * @param sessionId the unique identifier for the session to retrieve
   *
   * @return a future that yields the identified session when complete; the returned future may fail with a
   * {@link org.signal.registration.session.SessionNotFoundException}
   */
  public CompletableFuture<RegistrationSession> getRegistrationSession(final UUID sessionId) {
    return sessionRepository.getSession(sessionId);
  }

  /**
   * Selects a verification code sender for the destination phone number associated with the given session and sends a
   * verification code.
   *
   * @param messageTransport the transport via which to send a verification code to the destination phone number
   * @param sessionId the session within which to send (or re-send) a verification code
   * @param senderName if specified, a preferred sender to use
   * @param languageRanges a prioritized list of languages in which to send the verification code
   * @param clientType the type of client receiving the verification code
   *
   * @return a future that yields the updated registration session when the verification code has been sent and updates
   * to the session have been stored
   */
  public CompletableFuture<RegistrationSession> sendVerificationCode(final MessageTransport messageTransport,
      final UUID sessionId,
      @Nullable final String senderName,
      final List<Locale.LanguageRange> languageRanges,
      final ClientType clientType) {

    final RateLimiter<RegistrationSession> rateLimiter = switch (messageTransport) {
      case SMS -> sendSmsVerificationCodeRateLimiter;
      case VOICE -> sendVoiceVerificationCodeRateLimiter;
    };

    return sessionRepository.getSession(sessionId)
        .thenCompose(session -> {
          if (StringUtils.isNotBlank(session.getVerifiedCode())) {
            return CompletableFuture.failedFuture(new SessionAlreadyVerifiedException(session));
          }

          return rateLimiter.checkRateLimit(session)
              .thenCompose(ignored -> {
                try {
                  final Phonenumber.PhoneNumber phoneNumberFromSession =
                      PhoneNumberUtil.getInstance().parse(session.getPhoneNumber(), null);

                  final VerificationCodeSender sender = senderSelectionStrategy.chooseVerificationCodeSender(
                      messageTransport, phoneNumberFromSession, languageRanges, clientType, senderName);

                  return sender.sendVerificationCode(messageTransport,
                          phoneNumberFromSession,
                          languageRanges,
                          clientType)
                      .thenApply(sessionData -> new SenderAndSessionData(sender, sessionData));
                } catch (final NumberParseException e) {
                  // This should never happen because we're parsing a phone number from the session, which means we've
                  // parsed it successfully in the past
                  throw new CompletionException(e);
                }
              });
        })
        .thenCompose(senderAndSessionData -> sessionRepository.updateSession(sessionId, session -> {
          final RegistrationSession.Builder builder = session.toBuilder()
              .setCheckCodeAttempts(0)
              .setLastCheckCodeAttemptEpochMillis(0)
              .addRegistrationAttempts(buildRegistrationAttempt(senderAndSessionData.sender(),
                  messageTransport,
                  senderAndSessionData.sessionData(),
                  senderAndSessionData.sender().getAttemptTtl()));

          builder.setExpirationEpochMillis(getSessionExpiration(builder.build()).toEpochMilli());

          return builder.build();
        }));
  }

  private RegistrationAttempt buildRegistrationAttempt(final VerificationCodeSender sender,
      final MessageTransport messageTransport,
      final byte[] sessionData,
      final Duration ttl) {

    final org.signal.registration.session.MessageTransport sessionMessageTransport = switch (messageTransport) {
      case SMS -> org.signal.registration.session.MessageTransport.MESSAGE_TRANSPORT_SMS;
      case VOICE -> org.signal.registration.session.MessageTransport.MESSAGE_TRANSPORT_VOICE;
    };

    final Instant currentTime = clock.instant();

    return RegistrationAttempt.newBuilder()
        .setTimestampEpochMillis(currentTime.toEpochMilli())
        .setExpirationEpochMillis(currentTime.plus(ttl).toEpochMilli())
        .setSenderName(sender.getName())
        .setMessageTransport(sessionMessageTransport)
        .setSessionData(ByteString.copyFrom(sessionData))
        .build();
  }

  /**
   * Checks whether a client-provided verification code matches the expected verification code for a given registration
   * session. The code may be verified by communicating with an external service, by checking stored session data, or
   * by comparing against a previously-accepted verification code for the same session (i.e. in the case of retried
   * requests due to an interrupted connection).
   *
   * @param sessionId an identifier for a registration session against which to check a verification code
   * @param verificationCode a client-provided verification code
   *
   * @return a future that yields the updated registration; the session's {@code verifiedCode} field will be set if the
   * session has been successfully verified
   */
  public CompletableFuture<RegistrationSession> checkVerificationCode(final UUID sessionId, final String verificationCode) {
    return sessionRepository.getSession(sessionId)
        .thenCompose(session -> {
          // If a connection was interrupted, a caller may repeat a verification request. Check to see if we already
          // have a known verification code for this session and, if so, check the provided code against that code
          // instead of making a call upstream.
          if (StringUtils.isNotBlank(session.getVerifiedCode())) {
            return CompletableFuture.completedFuture(session);
          } else if (session.getRegistrationAttemptsCount() == 0) {
            return CompletableFuture.failedFuture(new NoVerificationCodeSentException(session));
          } else {
            return checkVerificationCodeRateLimiter.checkRateLimit(session).thenCompose(ignored -> {
              final RegistrationAttempt currentRegistrationAttempt =
                  session.getRegistrationAttempts(session.getRegistrationAttemptsCount() - 1);

              if (Instant.ofEpochMilli(currentRegistrationAttempt.getExpirationEpochMillis()).isBefore(clock.instant())) {
                return CompletableFuture.failedFuture(new AttemptExpiredException());
              }

              final VerificationCodeSender sender = sendersByName.get(currentRegistrationAttempt.getSenderName());

              if (sender == null) {
                throw new IllegalArgumentException("Unrecognized sender: " + currentRegistrationAttempt.getSenderName());
              }

              return sender.checkVerificationCode(verificationCode, currentRegistrationAttempt.getSessionData().toByteArray())
                  .exceptionally(throwable -> {
                    // The sender may view the submitted code as an illegal argument or may reject the attempt to check a
                    // code altogether. We can treat any case of "the sender got it, but said 'no'" the same way we would
                    // treat an accepted-but-incorrect code.
                    if (throwable instanceof SenderException) {
                      return false;
                    }

                    throw CompletionExceptions.wrap(throwable);
                  })
                  .thenCompose(verified -> recordCheckVerificationCodeAttempt(session, verified ? verificationCode : null));
            });
          }
        });
  }

  private CompletableFuture<RegistrationSession> recordCheckVerificationCodeAttempt(final RegistrationSession session,
      @Nullable final String verifiedCode) {

    return sessionRepository.updateSession(UUIDUtil.uuidFromByteString(session.getId()), s -> {
      final RegistrationSession.Builder builder = s.toBuilder()
          .setCheckCodeAttempts(session.getCheckCodeAttempts() + 1)
          .setLastCheckCodeAttemptEpochMillis(clock.millis());

      if (verifiedCode != null) {
        builder.setVerifiedCode(verifiedCode);
      }

      builder.setExpirationEpochMillis(getSessionExpiration(builder.build()).toEpochMilli());

      return builder.build();
    });
  }

  /**
   * Interprets a raw {@code RegistrationSession} and produces {@link RegistrationSessionMetadata} suitable for
   * presentation to remote callers.
   *
   * @param session the session to interpret
   *
   * @return session metadata suitable for presentation to remote callers
   */
  public RegistrationSessionMetadata buildSessionMetadata(final RegistrationSession session) {
    final boolean verified = StringUtils.isNotBlank(session.getVerifiedCode());

    final RegistrationSessionMetadata.Builder sessionMetadataBuilder = RegistrationSessionMetadata.newBuilder()
        .setSessionId(session.getId())
        .setE164(Long.parseLong(StringUtils.removeStart(session.getPhoneNumber(), "+")))
        .setVerified(verified)
        .setExpirationSeconds(Duration.between(clock.instant(), getSessionExpiration(session)).getSeconds());

    final NextActionDurations nextActionDurations = getNextActionDurations(session);

    nextActionDurations.nextSms().ifPresent(duration -> {
      sessionMetadataBuilder.setMayRequestSms(true);
      sessionMetadataBuilder.setNextSmsSeconds(duration.getSeconds());
    });

    nextActionDurations.nextVoiceCall().ifPresent(duration -> {
      sessionMetadataBuilder.setMayRequestVoiceCall(true);
      sessionMetadataBuilder.setNextVoiceCallSeconds(duration.getSeconds());
    });

    nextActionDurations.nextCodeCheck().ifPresent(duration -> {
      sessionMetadataBuilder.setMayCheckCode(true);
      sessionMetadataBuilder.setNextCodeCheckSeconds(duration.getSeconds());
    });

    return sessionMetadataBuilder.build();
  }

  @VisibleForTesting
  Instant getSessionExpiration(final RegistrationSession session) {
    final Instant expiration;

    if (StringUtils.isBlank(session.getVerifiedCode())) {
      final Instant currentTime = clock.instant();

      final List<Instant> candidateExpirations = new ArrayList<>(session.getRegistrationAttemptsList().stream()
          .map(attempt -> Instant.ofEpochMilli(attempt.getExpirationEpochMillis()))
          .toList());

      final NextActionDurations nextActionDurations = getNextActionDurations(session);

      nextActionDurations.nextSms()
          .map(duration -> currentTime.plus(duration).plus(SESSION_TTL_AFTER_LAST_ACTION))
          .ifPresent(candidateExpirations::add);

      nextActionDurations.nextVoiceCall()
          .map(duration -> currentTime.plus(duration).plus(SESSION_TTL_AFTER_LAST_ACTION))
          .ifPresent(candidateExpirations::add);

      nextActionDurations.nextCodeCheck()
          .map(duration -> currentTime.plus(duration).plus(SESSION_TTL_AFTER_LAST_ACTION))
          .ifPresent(candidateExpirations::add);

      expiration = candidateExpirations.stream().max(Comparator.naturalOrder())
          .orElseThrow(() -> new IllegalStateException("An unverified session must have an allowed next action or at least one registration attempt"));
    } else {
      // The session must have been verified as a result of the last check
      expiration = Instant.ofEpochMilli(session.getLastCheckCodeAttemptEpochMillis()).plus(SESSION_TTL_AFTER_LAST_ACTION);
    }

    return expiration;
  }

  @VisibleForTesting
  NextActionDurations getNextActionDurations(final RegistrationSession session) {
    final boolean verified = StringUtils.isNotBlank(session.getVerifiedCode());

    Optional<Duration> nextSms = Optional.empty();
    Optional<Duration> nextVoiceCall = Optional.empty();
    Optional<Duration> nextCodeCheck = Optional.empty();

    // If the session is already verified, callers can't request or check more verification codes
    if (!verified) {

      // Callers can only check codes if there's an active attempt
      if (session.getRegistrationAttemptsCount() > 0) {
        final Instant currentAttemptExpiration = Instant.ofEpochMilli(
            session.getRegistrationAttemptsList().get(session.getRegistrationAttemptsCount() - 1)
                .getExpirationEpochMillis());

        if (!clock.instant().isAfter(currentAttemptExpiration)) {
          nextCodeCheck = checkVerificationCodeRateLimiter.getDurationUntilActionAllowed(session).join();
        }
      }

      // Callers can't request more verification codes if they've exhausted their check attempts (since they can't check
      // any new codes they might receive)
      nextSms = sendSmsVerificationCodeRateLimiter.getDurationUntilActionAllowed(session).join();

      // Callers may not request codes via phone call until they've attempted an SMS
      final boolean hasSentSms = session.getRegistrationAttemptsList().stream().anyMatch(attempt ->
          attempt.getMessageTransport() == org.signal.registration.session.MessageTransport.MESSAGE_TRANSPORT_SMS);

      if (hasSentSms) {
        nextVoiceCall = sendVoiceVerificationCodeRateLimiter.getDurationUntilActionAllowed(session).join();
      }
    }

    return new NextActionDurations(nextSms, nextVoiceCall, nextCodeCheck);
  }
}
