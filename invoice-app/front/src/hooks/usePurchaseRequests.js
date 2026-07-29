import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '../api.js';

const INITIAL_STATE = {
  requests: [],
  status: 'idle',
  error: null,
};

export function usePurchaseRequests(enabled) {
  const [state, setState] = useState(INITIAL_STATE);
  const requestId = useRef(0);

  const load = useCallback(async () => {
    if (!enabled) return;

    const currentRequestId = ++requestId.current;
    setState((current) => ({ ...current, status: 'loading', error: null }));

    try {
      const response = await api.get('/invoice/purchase-requests');
      if (!Array.isArray(response.data)) {
        throw new Error('Unexpected purchase request response');
      }

      if (requestId.current === currentRequestId) {
        setState({
          requests: response.data,
          status: 'success',
          error: null,
        });
      }
    } catch (error) {
      if (requestId.current === currentRequestId) {
        setState({
          requests: [],
          status: 'error',
          error:
            error?.response?.data?.message ||
            'Could not load approved purchase requests.',
        });
      }
    }
  }, [enabled]);

  useEffect(() => {
    if (!enabled) {
      requestId.current += 1;
      setState(INITIAL_STATE);
      return undefined;
    }

    load();
    return () => {
      requestId.current += 1;
    };
  }, [enabled, load]);

  return { ...state, retry: load };
}
