import { useEffect, useState } from 'react';
import {
  Anchor,
  Button,
  Group,
  Modal,
  NumberInput,
  SegmentedControl,
  Stack,
  Text,
  TextInput,
} from '@mantine/core';
import { api } from '../api.js';
import { usePurchaseRequests } from '../hooks/usePurchaseRequests.js';
import PurchaseRequestSelect, {
  SupplierMismatchWarning,
} from './PurchaseRequestSelect.jsx';
import StatusBadge from './StatusBadge.jsx';

const fields = ['invoice_number', 'supplier', 'invoice_sum', 'invoice_sum_paid', 'invoice_status'];

export default function EditInvoiceModal({ invoice, onClose, onUpdated }) {
  const [form, setForm] = useState(null);
  const [error, setError] = useState(null);
  const [saving, setSaving] = useState(false);
  const [purchaseRequestSelection, setPurchaseRequestSelection] = useState(null);
  const [purchaseRequestChanged, setPurchaseRequestChanged] = useState(false);
  const {
    requests,
    status: purchaseRequestStatus,
    error: purchaseRequestError,
    retry: retryPurchaseRequests,
  } = usePurchaseRequests(Boolean(invoice));

  // Re-seed the form whenever the parent passes a new invoice in.
  useEffect(() => {
    if (invoice) {
      const seeded = {};
      fields.forEach((k) => { seeded[k] = invoice[k] ?? ''; });
      setForm(seeded);
      setError(null);
      setPurchaseRequestSelection(
        invoice.purchase_request_validated_at
          ? invoice.purchase_request_number
          : null
      );
      setPurchaseRequestChanged(false);
    } else {
      setForm(null);
      setPurchaseRequestSelection(null);
      setPurchaseRequestChanged(false);
    }
  }, [invoice]);

  if (!invoice || !form) return null;

  // See UploadInvoiceModal for the React-19 + StrictMode reasoning.
  const setField = (k) => (e) => {
    const value = typeof e === 'string' || typeof e === 'number'
      ? e
      : e?.currentTarget?.value ?? '';
    setForm((f) => ({ ...f, [k]: value }));
  };

  const selectPurchaseRequest = (code, request) => {
    if (!code || !request) return;
    setPurchaseRequestSelection(code);
    setPurchaseRequestChanged(true);
    setForm((current) => ({ ...current, supplier: request.supplier_name }));
  };

  const submit = async (e) => {
    e.preventDefault();
    setError(null);
    setSaving(true);
    try {
      // Send only the keys that actually changed — PUT is partial.
      const body = {};
      for (const k of fields) {
        if (form[k] !== '' && form[k] !== invoice[k]) body[k] = form[k];
      }
      if (purchaseRequestChanged && purchaseRequestSelection) {
        body.purchase_request_number = purchaseRequestSelection;
      }
      if (Object.keys(body).length > 0) {
        await api.put(`/invoice/${invoice.id}`, body);
      }
      onUpdated?.();
      onClose();
    } catch (err) {
      setError(err?.response?.data?.message || 'Could not save changes');
    } finally {
      setSaving(false);
    }
  };

  const downloadUrl = `${api.defaults.baseURL}/invoice/${invoice.id}/attachment`;
  const selectedPurchaseRequest = requests.find(
    (request) => request.request_code === purchaseRequestSelection
  );
  const hasStoredPurchaseRequest = Boolean(invoice.purchase_request_number);
  const relationshipIsValidated = Boolean(
    hasStoredPurchaseRequest && invoice.purchase_request_validated_at
  );

  return (
    <Modal
      opened={!!invoice}
      onClose={onClose}
      size="lg"
      centered
      title={
        <Group gap="sm">
          <Text fw={600} size="lg">{invoice.invoice_number}</Text>
          <StatusBadge status={form.invoice_status || invoice.invoice_status} />
        </Group>
      }
    >
      <form onSubmit={submit}>
        <Stack gap="md">
          {/* Attachment preview */}
          {invoice.attachment_filename ? (
            <Group
              justify="space-between"
              align="center"
              bg="gray.0"
              p="sm"
              style={{ borderRadius: 6 }}
            >
              <Group gap="sm" align="center">
                <Text size="xl">📎</Text>
                <Stack gap={0}>
                  <Text size="sm" fw={500}>{invoice.attachment_filename}</Text>
                  <Text size="xs" c="dimmed">
                    uploaded by {invoice.uploaded_by || 'unknown'} on{' '}
                    {invoice.created_at ? new Date(invoice.created_at).toLocaleDateString() : '—'}
                  </Text>
                </Stack>
              </Group>
              <Anchor href={downloadUrl} target="_blank" rel="noopener" size="sm">
                Download
              </Anchor>
            </Group>
          ) : (
            <Text size="sm" c="dimmed">No attachment uploaded.</Text>
          )}

          <Group grow align="flex-start">
            <TextInput
              label="Invoice number"
              value={form.invoice_number}
              onChange={setField('invoice_number')}
            />
            <TextInput
              label="Supplier"
              value={form.supplier}
              onChange={setField('supplier')}
            />
          </Group>
          <Group grow align="flex-start">
            <NumberInput
              label="Invoice sum"
              min={0}
              decimalScale={2}
              fixedDecimalScale
              value={form.invoice_sum}
              onChange={setField('invoice_sum')}
            />
            <NumberInput
              label="Paid"
              min={0}
              decimalScale={2}
              fixedDecimalScale
              value={form.invoice_sum_paid}
              onChange={setField('invoice_sum_paid')}
            />
          </Group>

          <Stack
            bg={
              relationshipIsValidated
                ? 'teal.0'
                : hasStoredPurchaseRequest
                  ? 'yellow.0'
                  : 'gray.0'
            }
            p="sm"
            gap={4}
            style={{ borderRadius: 6 }}
          >
            <Text size="xs" fw={600} c="dark.7">
              {relationshipIsValidated
                ? 'Validated purchase request'
                : hasStoredPurchaseRequest
                  ? 'Unverified legacy reference'
                  : 'No purchase request linked'}
            </Text>
            {hasStoredPurchaseRequest && (
              <Text size="sm" fw={500}>{invoice.purchase_request_number}</Text>
            )}
            <Text size="xs" c="dimmed">
              {relationshipIsValidated
                ? `Validated ${new Date(invoice.purchase_request_validated_at).toLocaleString()}`
                : hasStoredPurchaseRequest
                  ? 'Select an approved purchase request to verify this relationship.'
                  : 'This legacy invoice can be linked by selecting an approved purchase request.'}
            </Text>
          </Stack>

          <PurchaseRequestSelect
            requests={requests}
            status={purchaseRequestStatus}
            error={purchaseRequestError}
            value={purchaseRequestSelection}
            onChange={selectPurchaseRequest}
            onRetry={retryPurchaseRequests}
          />
          {purchaseRequestChanged && selectedPurchaseRequest && (
            <Text size="xs" c="teal">
              Saving will validate and link {selectedPurchaseRequest.request_code}.
            </Text>
          )}
          <SupplierMismatchWarning
            invoiceSupplier={form.supplier}
            purchaseRequest={selectedPurchaseRequest}
          />

          <Stack gap={6}>
            <Text size="sm" fw={500} c="dark.7">Status</Text>
            <SegmentedControl
              fullWidth
              data={['created', 'prepaid', 'paid']}
              value={form.invoice_status}
              onChange={(v) => setForm((f) => ({ ...f, invoice_status: v }))}
            />
          </Stack>

          {error && <Text c="red" size="sm">{error}</Text>}

          <Group justify="flex-end">
            <Button variant="default" onClick={onClose} disabled={saving}>
              Cancel
            </Button>
            <Button type="submit" loading={saving}>
              Save changes
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}
