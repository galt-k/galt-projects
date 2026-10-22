import type { CardSummary } from '../types/protocol';

/**
 * SE-FE02: Deterministic Finite State Machine (FSM)
 *
 * Invariants:
 * 1. Mutual Exclusivity: Zero independent booleans (isLoading, isError).
 *    Checkout is in strictly ONE valid state at any time.
 * 2. Referential Equality on Illegal Transitions: If an action is not valid
 *    for the current state, return exact `state` reference to prevent React re-renders.
 * 3. Exhaustiveness: TypeScript compiler enforces all states and actions are handled.
 */

// 1. Discriminated Union for State
export type CheckoutState =
  | { status: 'IDLE' }
  | { status: 'TOKENIZING'; correlationId: string }
  | { status: 'SUBMITTING_PAYMENT'; token: string; cardSummary: CardSummary }
  | { status: 'REQUIRES_3DS'; token: string; redirectUrl: string }
  | { status: 'SUCCEEDED'; transactionId: string }
  | { status: 'FAILED'; error: string; retryable: boolean };

// 2. Discriminated Union for Actions
export type CheckoutAction =
  | { type: 'START_TOKENIZATION'; correlationId: string }
  | { type: 'TOKENIZATION_SUCCESS'; token: string; cardSummary: CardSummary }
  | { type: 'TOKENIZATION_ERROR'; error: string }
  | { type: 'PAYMENT_SUCCESS'; transactionId: string }
  | { type: 'PAYMENT_REQUIRES_3DS'; redirectUrl: string }
  | { type: 'PAYMENT_ERROR'; error: string; retryable: boolean }
  | { type: 'THREE_DS_COMPLETED'; transactionId: string }
  | { type: 'THREE_DS_FAILED'; error: string }
  | { type: 'RETRY' };

export const initialCheckoutState: CheckoutState = { status: 'IDLE' };

/**
 * Compile-time exhaustiveness check helper.
 * If any state is missed in switch, TypeScript throws a type error.
 */
function assertNever(x: never): never {
  throw new Error(`Unexpected unhandled state: ${JSON.stringify(x)}`);
}

/**
 * Pure transition table for checkout lifecycle.
 */
export function checkoutReducer(
  state: CheckoutState,
  action: CheckoutAction
): CheckoutState {
  switch (state.status) {
    case 'IDLE':
      if (action.type === 'START_TOKENIZATION') {
        return {
          status: 'TOKENIZING',
          correlationId: action.correlationId,
        };
      }
      // Any other action is illegal in IDLE -> No-Op (referential equality preserved)
      return state;

    case 'TOKENIZING':
      if (action.type === 'TOKENIZATION_SUCCESS') {
        return {
          status: 'SUBMITTING_PAYMENT',
          token: action.token,
          cardSummary: action.cardSummary,
        };
      }
      if (action.type === 'TOKENIZATION_ERROR') {
        return {
          status: 'FAILED',
          error: action.error,
          retryable: true,
        };
      }
      // Double clicks dispatching START_TOKENIZATION while tokenizing are rejected
      return state;

    case 'SUBMITTING_PAYMENT':
      if (action.type === 'PAYMENT_SUCCESS') {
        return {
          status: 'SUCCEEDED',
          transactionId: action.transactionId,
        };
      }
      if (action.type === 'PAYMENT_REQUIRES_3DS') {
        return {
          status: 'REQUIRES_3DS',
          token: state.token,
          redirectUrl: action.redirectUrl,
        };
      }
      if (action.type === 'PAYMENT_ERROR') {
        return {
          status: 'FAILED',
          error: action.error,
          retryable: action.retryable,
        };
      }
      return state;

    case 'REQUIRES_3DS':
      if (action.type === 'THREE_DS_COMPLETED') {
        return {
          status: 'SUCCEEDED',
          transactionId: action.transactionId,
        };
      }
      if (action.type === 'THREE_DS_FAILED') {
        return {
          status: 'FAILED',
          error: action.error,
          retryable: true,
        };
      }
      return state;

    case 'FAILED':
      if (action.type === 'RETRY' && state.retryable) {
        return { status: 'IDLE' };
      }
      return state;

    case 'SUCCEEDED':
      // Terminal state: Zero transitions permitted
      return state;

    default:
      return assertNever(state);
  }
}
