import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, cleanup } from '@testing-library/react';
import { SecureCardElement } from './SecureCardElement';
import { TRUSTED_VAULT_ORIGIN } from '../types/protocol';

describe('SE-FE01: SecureCardElement (Correlation ID Invariants)', () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it('renders the iframe pointing to the trusted vault origin', () => {
    const { container } = render(
      <SecureCardElement
        isTokenizing={false}
        onSuccess={vi.fn()}
        onError={vi.fn()}
      />
    );

    const iframe = container.querySelector('iframe');
    expect(iframe).not.toBeNull();
    expect(iframe?.getAttribute('src')).toBe('http://localhost:3001/vault-card-input.html');
  });

  it('sends EXECUTE_TOKENIZATION with a correlationId when isTokenizing turns true', () => {
    const postMessageSpy = vi.fn();

    const { rerender, container } = render(
      <SecureCardElement
        isTokenizing={false}
        onSuccess={vi.fn()}
        onError={vi.fn()}
      />
    );

    const iframe = container.querySelector('iframe') as HTMLIFrameElement;
    Object.defineProperty(iframe, 'contentWindow', {
      value: { postMessage: postMessageSpy },
      configurable: true,
    });

    rerender(
      <SecureCardElement
        isTokenizing={true}
        onSuccess={vi.fn()}
        onError={vi.fn()}
      />
    );

    expect(postMessageSpy).toHaveBeenCalledWith(
      {
        type: 'EXECUTE_TOKENIZATION',
        correlationId: expect.any(String),
      },
      TRUSTED_VAULT_ORIGIN
    );
  });

  it('drops messages from untrusted origins without invoking callbacks', () => {
    const onSuccess = vi.fn();
    const onError = vi.fn();

    render(
      <SecureCardElement
        isTokenizing={false}
        onSuccess={onSuccess}
        onError={onError}
      />
    );

    // Attacker domain sends a message
    window.dispatchEvent(
      new MessageEvent('message', {
        origin: 'http://evil-attacker.com',
        data: {
          type: 'TOKENIZATION_SUCCESS',
          correlationId: 'stolen-uuid',
          token: 'stolen_tok_999',
          cardSummary: { brand: 'visa', last4: '4242', expMonth: 12, expYear: 2028 },
        },
      })
    );

    expect(onSuccess).not.toHaveBeenCalled();
    expect(onError).not.toHaveBeenCalled();
  });

  it('drops messages with mismatched or stale correlationId', () => {
    const onSuccess = vi.fn();
    const onError = vi.fn();
    const postMessageSpy = vi.fn();

    const { rerender, container } = render(
      <SecureCardElement
        isTokenizing={false}
        onSuccess={onSuccess}
        onError={onError}
      />
    );

    const iframe = container.querySelector('iframe') as HTMLIFrameElement;
    const mockWindow = { postMessage: postMessageSpy } as unknown as Window;
    Object.defineProperty(iframe, 'contentWindow', {
      value: mockWindow,
      configurable: true,
    });

    // Initiate tokenization (sets active correlationId in ref)
    rerender(
      <SecureCardElement
        isTokenizing={true}
        onSuccess={onSuccess}
        onError={onError}
      />
    );

    // Stale message arrives with a different correlationId
    window.dispatchEvent(
      new MessageEvent('message', {
        origin: TRUSTED_VAULT_ORIGIN,
        source: mockWindow,
        data: {
          type: 'TOKENIZATION_SUCCESS',
          correlationId: 'stale-outdated-uuid-999',
          token: 'tok_visa_stale',
          cardSummary: { brand: 'visa', last4: '1111', expMonth: 1, expYear: 2030 },
        },
      })
    );

    expect(onSuccess).not.toHaveBeenCalled();
    expect(onError).not.toHaveBeenCalled();
  });

  it('invokes onSuccess when verified response arrives with matching correlationId', () => {
    const onSuccess = vi.fn();
    const onError = vi.fn();
    const postMessageSpy = vi.fn();

    const { rerender, container } = render(
      <SecureCardElement
        isTokenizing={false}
        onSuccess={onSuccess}
        onError={onError}
      />
    );

    const iframe = container.querySelector('iframe') as HTMLIFrameElement;
    const mockWindow = { postMessage: postMessageSpy } as unknown as Window;
    Object.defineProperty(iframe, 'contentWindow', {
      value: mockWindow,
      configurable: true,
    });

    rerender(
      <SecureCardElement
        isTokenizing={true}
        onSuccess={onSuccess}
        onError={onError}
      />
    );

    // Extract the active correlationId sent to the iframe
    const sentCorrelationId = postMessageSpy.mock.calls[0][0].correlationId;

    // Send legitimate matching response
    window.dispatchEvent(
      new MessageEvent('message', {
        origin: TRUSTED_VAULT_ORIGIN,
        source: mockWindow,
        data: {
          type: 'TOKENIZATION_SUCCESS',
          correlationId: sentCorrelationId,
          token: 'tok_visa_4242',
          cardSummary: {
            brand: 'visa',
            last4: '4242',
            expMonth: 12,
            expYear: 2028,
          },
        },
      })
    );

    expect(onSuccess).toHaveBeenCalledWith('tok_visa_4242', {
      brand: 'visa',
      last4: '4242',
      expMonth: 12,
      expYear: 2028,
    });
    expect(onError).not.toHaveBeenCalled();
  });

  it('enforces single-use semantics by rejecting duplicate replayed messages', () => {
    const onSuccess = vi.fn();
    const onError = vi.fn();
    const postMessageSpy = vi.fn();

    const { rerender, container } = render(
      <SecureCardElement
        isTokenizing={false}
        onSuccess={onSuccess}
        onError={onError}
      />
    );

    const iframe = container.querySelector('iframe') as HTMLIFrameElement;
    const mockWindow = { postMessage: postMessageSpy } as unknown as Window;
    Object.defineProperty(iframe, 'contentWindow', {
      value: mockWindow,
      configurable: true,
    });

    rerender(
      <SecureCardElement
        isTokenizing={true}
        onSuccess={onSuccess}
        onError={onError}
      />
    );

    const sentCorrelationId = postMessageSpy.mock.calls[0][0].correlationId;
    const legitimateMessage = new MessageEvent('message', {
      origin: TRUSTED_VAULT_ORIGIN,
      source: mockWindow,
      data: {
        type: 'TOKENIZATION_SUCCESS',
        correlationId: sentCorrelationId,
        token: 'tok_visa_4242',
        cardSummary: { brand: 'visa', last4: '4242', expMonth: 12, expYear: 2028 },
      },
    });

    // 1st dispatch: succeeds
    window.dispatchEvent(legitimateMessage);
    expect(onSuccess).toHaveBeenCalledTimes(1);

    // 2nd dispatch: duplicate/replay dropped because ref was retired to null
    window.dispatchEvent(legitimateMessage);
    expect(onSuccess).toHaveBeenCalledTimes(1);
  });
});
