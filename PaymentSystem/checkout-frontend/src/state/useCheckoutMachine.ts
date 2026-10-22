import {useCallback, useEffect, useReducer, useRef } from 'react';
import {checkoutReducer, initialCheckoutState} from "./checkoutReducer.ts";

export function useCheckoutMachine() {
    const [state, dispatch] = useReducer(
        checkoutReducer,
        initialCheckoutState
    );

    const inFlightMutexRef = useRef(false);

    const submit = useCallback(
        (correlationId : string): boolean => {
            if(inFlightMutexRef.current) {
                return false;
            }

            //set sync before dispatch or any async work
            inFlightMutexRef.current = true;

            if(state.status != 'IDLE'){
                inFlightMutexRef.current = false;
                return false;
            }

            dispatch({type : 'START_TOKENIZATION', correlationId});
            return true;
        },
        [state.status]
    );

    useEffect(() => {
        if(state.status === 'SUCCEEDED'|| state.status === 'FAILED') {
            inFlightMutexRef.current = false;
        }
    }, [state.status]);

    return { state , submit };
}