import { useEffect, useState } from 'react';
import {
  Alert,
  Button,
  Group,
  Loader,
  Stack,
  Text,
} from '@mantine/core';
import { api } from '../api.js';
import StatusBadge from './StatusBadge.jsx';

const INITIAL_STATE = {
  status: 'loading',
  invoices: [],
};

export default function InvoiceStatusPanel({ purchaseRequestId }) {
  const [state, setState] = useState(INITIAL_STATE);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    const controller = new AbortController();

    setState(INITIAL_STATE);
    api
      .get(`/purchase-request/${purchaseRequestId}/invoices`, {
        signal: controller.signal,
      })
      .then((response) => {
        if (!controller.signal.aborted) {
          setState({
            status: 'success',
            invoices: Array.isArray(response.data) ? response.data : [],
          });
        }
      })
      .catch((error) => {
        if (controller.signal.aborted) return;

        setState({
          status: error?.response?.status === 403 ? 'forbidden' : 'error',
          invoices: [],
        });
      });

    return () => controller.abort();
  }, [purchaseRequestId, attempt]);

  if (state.status === 'forbidden') return null;

  return (
    <Stack gap="sm">
      <Stack gap={2}>
        <Text fw={600}>Invoice statuses</Text>
        <Text size="xs" c="dimmed">
          Statuses recorded by finance for invoices linked to this purchase
          request.
        </Text>
      </Stack>

      {state.status === 'loading' && (
        <Group gap="xs">
          <Loader size="xs" />
          <Text size="sm" c="dimmed">
            Loading invoice statuses…
          </Text>
        </Group>
      )}

      {state.status === 'success' && state.invoices.length === 0 && (
        <Text size="sm" c="dimmed">
          No linked invoices yet.
        </Text>
      )}

      {state.status === 'success' && state.invoices.length > 0 && (
        <Stack gap="xs">
          {state.invoices.map((invoice) => (
            <Group key={invoice.id} justify="space-between" wrap="nowrap">
              <Text size="sm">
                {invoice.invoice_number?.trim() || `Invoice ${invoice.id}`}
              </Text>
              <StatusBadge status={invoice.invoice_status} />
            </Group>
          ))}
        </Stack>
      )}

      {state.status === 'error' && (
        <Alert color="red" title="Invoice information temporarily unavailable">
          <Stack gap="xs">
            <Text size="sm">
              Purchase request details remain available. Try loading the
              invoice statuses again.
            </Text>
            <Button
              type="button"
              variant="light"
              color="red"
              size="xs"
              onClick={() => setAttempt((value) => value + 1)}
              style={{ alignSelf: 'flex-start' }}
            >
              Retry
            </Button>
          </Stack>
        </Alert>
      )}

      <Text size="xs" c="dimmed">
        These statuses do not confirm that the purchase request as a whole is
        fully paid.
      </Text>
    </Stack>
  );
}
