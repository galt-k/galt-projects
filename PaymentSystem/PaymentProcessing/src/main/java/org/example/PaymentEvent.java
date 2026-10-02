package org.example;

public sealed interface PaymentEvent {
    record RequiresConfirmation() implements PaymentEvent {}
    record AuthorizationStarted() implements PaymentEvent {}
    record AuthorizationSucceeded() implements PaymentEvent {}
    record AuthorizationFailed() implements PaymentEvent {}
    record CaptureStarted() implements PaymentEvent {}
    record CaptureSucceeded() implements PaymentEvent {}
    record CaptureFailed() implements PaymentEvent {}
    record LedgerPosted() implements PaymentEvent {}
    record FraudRejected()  implements PaymentEvent {}
    record Cancelled() implements PaymentEvent {}
}
