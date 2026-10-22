// Wire protocol contract between the Merchant Host application and the Vault Iframe

export const TRUSTED_VAULT_ORIGIN = 'http://localhost:3001';

// 1. Inbound Commands (Merchant -> Vault), the command that Merchant sends to Vault.
export type VaultInboundMessage = {
  type: 'EXECUTE_TOKENIZATION';
  correlationId: string;
};

// 2. Outbound Events (Vault -> Merchant)
export type CardSummary = {
  brand: 'visa' | 'mastercard' | 'amex' | 'unknown';
  last4: string;
  expMonth: number;
  expYear: number;
};

export type VaultOutboundMessage =
  | {
      type: 'TOKENIZATION_SUCCESS';
      correlationId: string;
      token: string;
      cardSummary: CardSummary;
    }
  | {
      type: 'TOKENIZATION_ERROR';
      correlationId: string;
      message: string;
      code: 'INCOMPLETE_CARD' | 'EXPIRED_CARD' | 'INVALID_CVC';
    };

/**
 * Runtime Type Guard:
 * Protects against malformed, null, or attacker-crafted payloads across postMessage.
 * Returns true only if data strictly conforms to VaultOutboundMessage.
 */
export function isVaultOutboundMessage(data: unknown): data is VaultOutboundMessage {
  if (typeof data !== 'object' || data === null) {
    return false;
  }

  const msg = data as Record<string, unknown>;

  if(typeof msg.correlationId != "string"){
    return false;
  }

  if (msg.type === 'TOKENIZATION_SUCCESS') {
    return (
      typeof msg.token === 'string' &&
      typeof msg.cardSummary === 'object' &&
      msg.cardSummary !== null &&
      typeof (msg.cardSummary as Record<string, unknown>).last4 === 'string'
    );
  }

  if (msg.type === 'TOKENIZATION_ERROR') {
    return typeof msg.message === 'string' && typeof msg.code === 'string';
  }

  return false;
}
