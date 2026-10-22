import { useRef, useEffect } from 'react';
import {
  TRUSTED_VAULT_ORIGIN,
  isVaultOutboundMessage,
  type CardSummary,
} from '../types/protocol';

export interface SecureCardElementProps {
  isTokenizing: boolean;
  onSuccess: (token: string, cardSummary: CardSummary) => void;
  onError: (errorMessage: string) => void;
}

/**
 * SE-FE01: Zero-Trust Secure Card Element
 *
 * Invariants:
 * 1. Hosts the payment card input inside a cross-origin iframe (http://localhost:3001)
 *    so raw PAN/CVV never touches the merchant DOM or JS heap (SAQ-A qualification).
 * 2. Communicates via postMessage RPC with strict origin and source verification.
 * 3. Uses pure props/callbacks:
 *    - isTokenizing: triggers outbound command to the vault iframe
 *    - onSuccess: surfaces the safe token and card summary
 *    - onError: surfaces tokenization failures
 */
export function SecureCardElement({
  isTokenizing,
  onSuccess,
  onError,
}: SecureCardElementProps) {
  const iframeRef = useRef<HTMLIFrameElement>(null);
  const activeCorrelationIdref = useRef<string | null>(null);

  // 1. REACTION: When isTokenizing turns true, dispatch command across the bridge
  useEffect(() => {
    if (isTokenizing) {

      const correlationId = crypto.randomUUID();
      activeCorrelationIdref.current = correlationId;

      iframeRef.current?.contentWindow?.postMessage(
        { type: 'EXECUTE_TOKENIZATION', correlationId },
        TRUSTED_VAULT_ORIGIN
      );
    }
  }, [isTokenizing]);

  // 2. REACTION: Listen for the Vault iframe's secure postMessage reply
  useEffect(() => {
    function handleMessage(event: MessageEvent) {
      // Security Check A: Verify Origin
      if (event.origin !== TRUSTED_VAULT_ORIGIN) {
        return;
      }

      // Security Check B: Verify Source Window
      if (event.source !== iframeRef.current?.contentWindow) {
        return;
      }


      // Security Check C: Runtime Type Guard
      if (!isVaultOutboundMessage(event.data)) {
        return;
      }

      // Security check D: correlationID Matching
      if(event.data.correlationId != activeCorrelationIdref.current){
        return;
      }

      activeCorrelationIdref.current = null;

      // Dispatch to parent callbacks based on Discriminated Union type
      if (event.data.type === 'TOKENIZATION_SUCCESS') {
        onSuccess(event.data.token, event.data.cardSummary);
      } else if (event.data.type === 'TOKENIZATION_ERROR') {
        onError(event.data.message);
      }
    }

    window.addEventListener('message', handleMessage);

    // Teardown: Prevent memory leaks and duplicate listeners
    return () => {
      window.removeEventListener('message', handleMessage);
    };
  }, [onSuccess, onError]);

  return (
    <div style={{ width: '100%', border: '1px solid #cbd5e1', borderRadius: '6px', overflow: 'hidden' }}>
      <iframe
        ref={iframeRef}
        src="http://localhost:3001/vault-card-input.html"
        title="Secure Card Input"
        style={{
          width: '100%',
          height: '110px',
          border: 'none',
          display: 'block',
        }}
      />
    </div>
  );
}
