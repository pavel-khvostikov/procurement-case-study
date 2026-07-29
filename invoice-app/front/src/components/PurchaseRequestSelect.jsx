import {
  Alert,
  Button,
  Group,
  Loader,
  Select,
  Stack,
  Text,
} from '@mantine/core';

export default function PurchaseRequestSelect({
  requests,
  status,
  error,
  value,
  onChange,
  onRetry,
  required = false,
  disabled = false,
}) {
  const requestByCode = new Map(
    requests.map((request) => [request.request_code, request])
  );
  const data = requests.map((request) => ({
    value: request.request_code,
    label: [
      request.request_code,
      request.request_name,
      request.supplier_name,
      request.request_author,
    ].join(' — '),
  }));
  const unavailable = status !== 'success' || requests.length === 0;

  return (
    <Stack gap={6}>
      <Select
        label="Purchase request"
        description="Search approved requests by code, name, supplier, or requester"
        placeholder={
          status === 'loading'
            ? 'Loading approved purchase requests…'
            : 'Select an approved purchase request'
        }
        searchable
        required={required}
        data={data}
        value={value || null}
        onChange={(code) => onChange(code, requestByCode.get(code) || null)}
        disabled={disabled || unavailable}
        rightSection={status === 'loading' ? <Loader size={16} /> : undefined}
        nothingFoundMessage="No matching approved purchase request"
        maxDropdownHeight={320}
        renderOption={({ option }) => {
          const request = requestByCode.get(option.value);
          if (!request) return option.label;

          return (
            <Stack gap={1} py={3}>
              <Group gap="xs" wrap="nowrap">
                <Text size="sm" fw={600}>{request.request_code}</Text>
                <Text size="sm">{request.request_name}</Text>
              </Group>
              <Text size="xs" c="dimmed">
                {request.supplier_name} · requested by {request.request_author}
              </Text>
            </Stack>
          );
        }}
      />

      {status === 'loading' && (
        <Text size="xs" c="dimmed">Loading approved purchase requests…</Text>
      )}
      {status === 'success' && requests.length === 0 && (
        <Text size="sm" c="dimmed">
          No approved purchase requests are available.
        </Text>
      )}
      {status === 'error' && (
        <Alert color="red" title="Purchase requests unavailable">
          <Stack gap="xs">
            <Text size="sm">{error}</Text>
            <Button
              type="button"
              variant="light"
              color="red"
              size="xs"
              onClick={onRetry}
              style={{ alignSelf: 'flex-start' }}
            >
              Retry
            </Button>
          </Stack>
        </Alert>
      )}
    </Stack>
  );
}

export function SupplierMismatchWarning({ invoiceSupplier, purchaseRequest }) {
  if (!purchaseRequest) return null;

  const invoiceValue = (invoiceSupplier || '').trim();
  const requestValue = (purchaseRequest.supplier_name || '').trim();
  if (invoiceValue === requestValue) return null;

  return (
    <Alert color="yellow" title="Supplier differs from the purchase request">
      <Text size="sm">
        PR supplier: <strong>{requestValue}</strong>. Invoice supplier:{' '}
        <strong>{invoiceValue || '(blank)'}</strong>. You can still save.
      </Text>
    </Alert>
  );
}
